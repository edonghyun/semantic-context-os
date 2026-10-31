package io.scos.client.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("state.sqlite 저장소는 원문과 체크포인트를 함께 보존한다(CG-6)")
class StateStoreTest {
  private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
  private static final ContentCapture CAPTURE = new LocalContentCapture();

  /** 잠금을 기다리지 않은 열기의 상한(busy_timeout 5초보다 충분히 짧다). */
  private static final Duration PROMPT_OPEN = Duration.ofSeconds(2);

  private static RawItem item(String id, String type, String doc, String json) {
    return new RawItem(
            id,
            type,
            "v1",
            Optional.empty(),
            Optional.empty(),
            Optional.of("2026-10-27T10:00:00.000Z"),
            NOW,
            "{\"documentId\":\"" + doc + "\"}",
            Optional.empty())
        .withContent(CAPTURE.json("application/json", json));
  }

  private static CollectionPage page(Checkpoint next, RawMutation... mutations) {
    return new CollectionPage(List.of(mutations), List.of(), next);
  }

  @Test
  @DisplayName("V2 원본은 마이그레이션 뒤 JSON 미디어 타입으로 읽힌다")
  void upgradesLegacyJsonContent(@TempDir Path dir) throws Exception {
    // given
    var scenario = new StateStoreUpgradeScenario(dir);
    scenario.givenLegacyJsonContent();

    // when
    List<RawItem> restored = scenario.reopenItems();

    // then
    assertThat(restored.getFirst().content())
        .contains(new RawContent.Inline("application/json", "{\"text\":\"합성 메모\"}"));
  }

  @Test
  @DisplayName("아는 버전보다 새 state 스키마는 버전 문맥과 함께 거부한다")
  void refusesNewerSchema(@TempDir Path dir) throws Exception {
    // given
    var scenario = new StateStoreUpgradeScenario(dir);
    scenario.givenNewerSchema();
    // when
    Throwable failure = catchThrowable(scenario::open);
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("state.sqlite schema 999 is newer than 3");
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 70_000})
  @DisplayName("Inline과 Blob으로 캡처한 내용의 미디어 타입을 다시 읽는다")
  void preservesCapturedMediaType(int size, @TempDir Path dir) {
    // given
    RawItem original =
        item("a", "t", "doc/a", "{}")
            .withContent(CAPTURE.json("text/plain;charset=utf-8", "가".repeat(size)));
    try (StateStore store = StateStore.open(dir.resolve("state.sqlite"))) {
      store.commit("s", page(new Checkpoint(1, "{}"), new RawMutation.Upsert(original)));

      // when
      List<RawItem> restored = store.items("s", Set.of("doc/a"), Set.of("t"));

      // then
      assertThat(restored.getFirst().content().orElseThrow().mediaType())
          .isEqualTo("text/plain;charset=utf-8");
    }
  }

  @Test
  @DisplayName("변경과 체크포인트를 함께 반영하고 다시 열어도 남는다")
  void commitsPageAtomically(@TempDir Path dir) {
    // given
    Path file = dir.resolve("state.sqlite");
    Checkpoint next = new Checkpoint(1, "{\"n\":1}");

    // when
    try (StateStore store = StateStore.open(file)) {
      store.commit(
          "s",
          page(
              next,
              new RawMutation.Upsert(item("a#1", "t", "doc/a", "{\"x\":1}")),
              new RawMutation.Upsert(item("a#2", "line", "doc/a", "{\"x\":2}"))));
    }

    // then
    try (StateStore store = StateStore.open(file)) {
      assertThat(store.checkpoint("s")).contains(next);
      assertThat(store.documentIds("s")).containsExactly("doc/a");
      assertThat(store.content("s", "a#1")).contains("{\"x\":1}");
    }
  }

  @Test
  @DisplayName("지정한 문서와 자원 종류의 항목만 읽는다")
  void readsOnlyRequestedTypes(@TempDir Path dir) {
    // given
    try (StateStore store = StateStore.open(dir.resolve("state.sqlite"))) {
      store.commit(
          "s",
          page(
              new Checkpoint(1, "{}"),
              new RawMutation.Upsert(item("a#1", "t", "doc/a", "{\"x\":1}")),
              new RawMutation.Upsert(item("a#2", "line", "doc/a", "{\"x\":2}")),
              new RawMutation.Upsert(item("b#1", "t", "doc/b", "{\"x\":3}"))));

      // when
      List<RawItem> items = store.items("s", Set.of("doc/a"), Set.of("t"));

      // then
      assertThat(items).extracting(RawItem::externalId).containsExactly("a#1");
      assertThat(items.getFirst().metadataJson()).isEqualTo("{\"documentId\":\"doc/a\"}");
    }
  }

  @Test
  @DisplayName("삭제 변경은 항목을 지우고 닿은 문서를 알려 준다")
  void deleteRemovesItem(@TempDir Path dir) {
    // given
    try (StateStore store = StateStore.open(dir.resolve("state.sqlite"))) {
      store.commit(
          "s",
          page(
              new Checkpoint(1, "{}"),
              new RawMutation.Upsert(item("a", "t", "doc/a", "{\"x\":1}"))));

      // when
      Set<String> touched =
          store.commit(
              "s",
              page(
                  new Checkpoint(1, "{\"n\":2}"),
                  new RawMutation.Delete(
                      "a", Optional.empty(), Optional.empty(), Optional.empty(), NOW)));

      // then
      assertThat(touched).containsExactly("doc/a");
      assertThat(store.documentIds("s")).isEmpty();
    }
  }

  @Test
  @DisplayName("64KiB를 넘는 내용은 Blob으로 잡히지만 state에는 바이트로 남는다")
  void largeContentIsKept(@TempDir Path dir) {
    // given
    String big = "{\"t\":\"" + "가".repeat(30_000) + "\"}";

    // when
    RawContent small = CAPTURE.json("application/json", "{}");
    RawContent large = CAPTURE.json("application/json", big);
    try (StateStore store = StateStore.open(dir.resolve("state.sqlite"))) {
      store.commit(
          "s", page(new Checkpoint(1, "{}"), new RawMutation.Upsert(item("a", "t", "doc/a", big))));

      // then
      assertThat(small).isInstanceOf(RawContent.Inline.class);
      assertThat(large).isInstanceOf(RawContent.Blob.class);
      assertThat(store.content("s", "a")).contains(big);
    }
  }

  @Nested
  @DisplayName("스키마가 최신인 파일에 다른 연결이 쓰기 트랜잭션을 쥐고 있어도")
  class OpenWhileWriterHolds {
    private Path file;
    private Connection writer;

    @BeforeEach
    void givenHeldWriteTransaction(@TempDir Path dir) throws Exception {
      file = dir.resolve("state.sqlite");
      StateStore.open(file).close();
      writer = DriverManager.getConnection("jdbc:sqlite:" + file);
      try (Statement st = writer.createStatement()) {
        st.execute("BEGIN IMMEDIATE");
        st.execute(
            "INSERT INTO checkpoints (source, schema_version, value_json) VALUES ('s', 1, '{}')");
      }
    }

    @AfterEach
    void releaseWriter() throws Exception {
      try (Statement st = writer.createStatement()) {
        st.execute("ROLLBACK");
      }
      writer.close();
    }

    @Test
    @DisplayName("쓰기 잠금을 기다리지 않고 바로 열린다")
    void opensPromptly() {
      // when
      long started = System.nanoTime();
      Optional<Checkpoint> checkpoint;
      try (StateStore store = StateStore.open(file)) {
        checkpoint = store.checkpoint("s");
      }
      Duration took = Duration.ofNanos(System.nanoTime() - started);

      // then
      assertThat(checkpoint).isEmpty();
      assertThat(took).isLessThan(PROMPT_OPEN);
    }
  }
}
