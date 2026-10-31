package io.scos.client.source.transcript;

import static io.scos.client.source.CollectedPages.deletes;
import static io.scos.client.source.CollectedPages.item;
import static io.scos.client.source.CollectedPages.last;
import static io.scos.client.source.CollectedPages.upserts;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.ClientJson;
import io.scos.client.source.SourceAdapterScenario;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RejectedItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("트랜스크립트는 원본을 가려 증분으로 수집한다(E1, E2)")
class TranscriptAdapterTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);

  @TempDir Path root;
  private Path dir;
  private TranscriptAdapter adapter;
  private SourceAdapterScenario scenario;

  @BeforeEach
  void setUp() throws IOException {
    dir = Files.createDirectories(root.resolve("projects/d"));
    adapter = new TranscriptAdapter(root.resolve("projects"), CLOCK);
    scenario = new SourceAdapterScenario(adapter, CLOCK);
  }

  private void write(Path file, String content) throws IOException {
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private void append(Path file, String content) throws IOException {
    Files.writeString(
        file,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND,
        StandardOpenOption.CREATE);
  }

  private List<CollectionPage> collect(Optional<Checkpoint> checkpoint) {
    return scenario.collect(checkpoint);
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "17", "[]", "\"text\""})
  @DisplayName("JSON 객체가 아닌 줄은 잘못된 항목으로 거절한다")
  void rejectsNonObjectJson(String text) throws IOException {
    // given
    write(dir.resolve("s1.jsonl"), text + "\n");
    // when
    List<CollectionPage> pages = collect(Optional.empty());
    // then
    assertThat(pages)
        .flatExtracting(CollectionPage::rejections)
        .extracting(RejectedItem::failureKind)
        .containsExactly(RejectedItem.FailureKind.INVALID_ITEM);
  }

  @Test
  @DisplayName("원천 종류는 클로드 트랜스크립트다")
  void identifiesTranscriptKind() {
    // given: 트랜스크립트 어댑터

    // when
    String kind = adapter.kind();

    // then
    assertThat(kind).isEqualTo("claude-transcript");
  }

  @Test
  @DisplayName("항상 증분 범위다")
  void coverageIsIncremental() {
    // when
    CollectionCoverage coverage =
        adapter.coverage(scenario.config(), CollectionMode.SNAPSHOT, Optional.empty());

    // then
    assertThat(coverage).isEqualTo(CollectionCoverage.INCREMENTAL);
  }

  @Test
  @DisplayName("두 번째 수집은 새로 붙은 줄만 낸다")
  void appendsOnly() throws IOException {
    // given
    Path file = dir.resolve("s1.jsonl");
    write(
        file,
        TranscriptLines.user("1").build()
            + "\n"
            + TranscriptLines.user("2").build()
            + "\n"
            + TranscriptLines.user("3").build()
            + "\n");
    List<CollectionPage> first = collect(Optional.empty());

    // when
    append(
        file, TranscriptLines.user("4").build() + "\n" + TranscriptLines.user("5").build() + "\n");
    List<CollectionPage> second = collect(last(first));

    // then
    assertThat(upserts(second)).containsExactly("d/s1#3", "d/s1#4");
    assertThat(deletes(second)).isEmpty();
  }

  @Test
  @DisplayName("개행 없는 마지막 줄은 다음 수집까지 미룬다")
  void defersPartialLine() throws IOException {
    // given
    Path file = dir.resolve("s1.jsonl");
    write(
        file,
        TranscriptLines.user("a").build()
            + "\n"
            + TranscriptLines.user("b").build()
            + "\n{\"type\":\"user\"");
    List<CollectionPage> first = collect(Optional.empty());

    // when
    append(file, ",\"message\":{\"content\":\"c\"}}\n");
    List<CollectionPage> second = collect(last(first));

    // then
    assertThat(upserts(second)).containsExactly("d/s1#2");
    assertThat(second.get(0).rejections()).isEmpty();
  }

  @Test
  @DisplayName("미완성 줄뿐이면 페이지를 내지 않고 커서도 그대로다")
  void partialOnlyEmitsNothing() throws IOException {
    // given
    write(dir.resolve("s1.jsonl"), "{\"type\":\"user\"");

    // when
    List<CollectionPage> pages = collect(Optional.empty());

    // then
    assertThat(pages).isEmpty();
  }

  @Test
  @DisplayName("파일이 줄거나 첫 줄이 바뀌면 처음부터 다시 읽고 남는 줄 번호를 지운다")
  void rereadsRewrittenFile() throws IOException {
    // given
    Path file = dir.resolve("s1.jsonl");
    write(
        file,
        TranscriptLines.user("1").build()
            + "\n"
            + TranscriptLines.user("2").build()
            + "\n"
            + TranscriptLines.user("3").build()
            + "\n"
            + TranscriptLines.user("4").build()
            + "\n"
            + TranscriptLines.user("5").build()
            + "\n");
    List<CollectionPage> first = collect(Optional.empty());

    // when
    write(
        file,
        TranscriptLines.user("x").build()
            + "\n"
            + TranscriptLines.user("y").build()
            + "\n"
            + TranscriptLines.user("z").build()
            + "\n");
    List<CollectionPage> second = collect(last(first));

    // then
    assertThat(upserts(second)).containsExactly("d/s1#0", "d/s1#1", "d/s1#2");
    assertThat(deletes(second)).containsExactly("d/s1#3", "d/s1#4");
  }

  @Test
  @DisplayName("크기가 같아도 첫 줄이 바뀌면 다시 읽는다")
  void rereadsWhenHeadChanges() throws IOException {
    // given
    Path file = dir.resolve("s1.jsonl");
    write(
        file, TranscriptLines.user("1").build() + "\n" + TranscriptLines.user("2").build() + "\n");
    List<CollectionPage> first = collect(Optional.empty());

    // when
    write(
        file, TranscriptLines.user("9").build() + "\n" + TranscriptLines.user("2").build() + "\n");
    List<CollectionPage> second = collect(last(first));

    // then
    assertThat(upserts(second)).containsExactly("d/s1#0", "d/s1#1");
    assertThat(deletes(second)).isEmpty();
  }

  @Test
  @DisplayName("파일이 사라져도 삭제를 내지 않는다")
  void keepsVanishedSession() throws IOException {
    // given
    Path file = dir.resolve("s1.jsonl");
    write(file, TranscriptLines.user("1").build() + "\n");
    List<CollectionPage> first = collect(Optional.empty());

    // when
    Files.delete(file);
    List<CollectionPage> second = collect(last(first));

    // then
    assertThat(second).isEmpty();
  }

  @Test
  @DisplayName("서브에이전트 jsonl은 수집하지 않는다")
  void skipsSubagents() throws IOException {
    // given
    write(dir.resolve("s1.jsonl"), TranscriptLines.user("1").build() + "\n");
    Path sub = Files.createDirectories(dir.resolve("s1/subagents"));
    write(sub.resolve("a.jsonl"), TranscriptLines.user("sub").build() + "\n");

    // when
    List<CollectionPage> pages = collect(Optional.empty());

    // then
    assertThat(upserts(pages)).containsExactly("d/s1#0");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("io.scos.client.source.transcript.TranscriptLineCases#nonProjected")
  @DisplayName("투영에 쓰지 않는 줄은 원문 보존용 타입이다")
  void classifiesNonProjectedLines(String kind, String line) throws IOException {
    // given
    write(dir.resolve("s1.jsonl"), line + "\n");

    // when
    RawItem item = item(collect(Optional.empty()), "d/s1#0");

    // then
    assertThat(item.resourceType()).isEqualTo("claude.transcript.line");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("io.scos.client.source.transcript.TranscriptLineCases#projected")
  @DisplayName("본문과 제목과 질문 답변은 투영용 타입이다")
  void classifiesProjectedLines(String kind, String line) throws IOException {
    // given
    write(dir.resolve("s1.jsonl"), line + "\n");

    // when
    RawItem item = item(collect(Optional.empty()), "d/s1#0");

    // then
    assertThat(item.resourceType()).isEqualTo("claude.transcript.turn");
  }

  @Test
  @DisplayName("ai-title의 원본 갱신 시각은 비어 있다")
  void titleHasNoSourceTimestamp() throws IOException {
    // given
    write(dir.resolve("s1.jsonl"), TranscriptLines.aiTitle("합성 제목").build() + "\n");

    // when
    RawItem item = item(collect(Optional.empty()), "d/s1#0");

    // then
    assertThat(item.sourceUpdatedAt()).isEmpty();
  }

  @Test
  @DisplayName("큰 줄은 가린 내용을 Blob으로 캡처한다")
  void capturesLargeLineAsBlob() throws IOException {
    // given
    write(
        dir.resolve("s1.jsonl"),
        TranscriptLines.user("가".repeat(23000) + " sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA").build()
            + "\n");

    // when
    RawItem item = item(collect(Optional.empty()), "d/s1#0");

    // then
    assertThat(item.content().orElseThrow())
        .isInstanceOfSatisfying(
            RawContent.Blob.class,
            blob ->
                assertThat(
                        ClientJson.MAPPER
                            .readTree(new String(blob.data().orElseThrow(), StandardCharsets.UTF_8))
                            .path("message")
                            .path("content")
                            .get(0)
                            .path("text")
                            .asString())
                    .isEqualTo("가".repeat(23000) + " [REDACTED]"));
  }

  @Nested
  @DisplayName("자격 증명과 원본 시각이 있는 사용자 줄이 있으면")
  class SecretUserLine {
    @BeforeEach
    void givenSecretUserLine() throws IOException {
      write(
          dir.resolve("s1.jsonl"),
          TranscriptLines.user("key sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA")
                  .timestamp("2026-10-01T00:00:00Z")
                  .build()
              + "\n");
    }

    @Test
    @DisplayName("원본 줄의 본문은 자격 증명을 가린 형태로 저장한다")
    void storesRedactedLine() {
      // when
      RawItem item = item(collect(Optional.empty()), "d/s1#0");
      // then
      RawContent.Inline content = (RawContent.Inline) item.content().orElseThrow();
      assertThat(
              ClientJson.MAPPER
                  .readTree(content.valueJson())
                  .path("message")
                  .path("content")
                  .path(0)
                  .path("text")
                  .asString())
          .isEqualTo("key [REDACTED]");
    }

    @Test
    @DisplayName("원본 줄에는 세션 문서 식별자가 붙는다")
    void carriesSessionDocumentId() {
      // when
      RawItem item = item(collect(Optional.empty()), "d/s1#0");
      // then
      assertThat(item.metadataJson()).isEqualTo("{\"documentId\":\"transcript/d/s1\"}");
    }

    @Test
    @DisplayName("원본 줄의 갱신 시각은 원본 시각을 따른다")
    void preservesSourceTimestamp() {
      // when
      RawItem item = item(collect(Optional.empty()), "d/s1#0");
      // then
      assertThat(item.sourceUpdatedAt()).contains("2026-10-01T00:00:00Z");
    }
  }

  @Test
  @DisplayName("파싱할 수 없는 줄은 원문 없이 거부하고 줄 번호는 전진한다")
  void rejectsUnparsableLine() throws IOException {
    // given
    write(
        dir.resolve("s1.jsonl"),
        "not json secret-xyz\n" + TranscriptLines.user("ok").build() + "\n");

    // when
    List<CollectionPage> pages = collect(Optional.empty());

    // then
    assertThat(pages.get(0).rejections()).hasSize(1);
    assertThat(pages.get(0).rejections().get(0).quarantined()).isEmpty();
    assertThat(upserts(pages)).containsExactly("d/s1#1");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("키가 겹친 줄은 트리가 원문 전체를 대표하지 않으므로 원문 없이 거부한다")
  @ValueSource(
      strings = {
        "{\"type\":\"user\",\"m\":\"sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA\",\"m\":\"hi\"}",
        "{\"type\":\"user\",\"password\":\"Zq9xW7vK2mP4\",\"password\":\"\"}"
      })
  void rejectsDuplicateKeyLineWithoutRaw(String line) throws IOException {
    // given
    write(dir.resolve("s1.jsonl"), line + "\n");

    // when
    List<CollectionPage> pages = collect(Optional.empty());

    // then
    assertThat(upserts(pages)).isEmpty();
    assertThat(pages.get(0).rejections().get(0).quarantined()).isEmpty();
  }

  @Test
  @DisplayName("스냅숏 모드도 줄어든 파일의 남는 줄 번호를 지운다")
  void snapshotDeletesLeftovers() throws IOException {
    // given
    Path file = dir.resolve("s1.jsonl");
    write(
        file,
        TranscriptLines.user("1").build()
            + "\n"
            + TranscriptLines.user("2").build()
            + "\n"
            + TranscriptLines.user("3").build()
            + "\n");
    List<CollectionPage> first = collect(Optional.empty());

    // when
    write(file, TranscriptLines.user("x").build() + "\n");
    List<CollectionPage> second = scenario.collect(last(first), CollectionMode.SNAPSHOT);

    // then
    assertThat(deletes(second)).containsExactly("d/s1#1", "d/s1#2");
  }

  @Test
  @DisplayName("1000줄마다 페이지를 나누고 체크포인트가 줄 경계를 따라 전진한다")
  void splitsPagesAtLineBoundaries() throws IOException {
    // given
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 2500; i++) sb.append(TranscriptLines.user("m" + i).build()).append("\n");
    write(dir.resolve("s1.jsonl"), sb.toString());

    // when
    List<CollectionPage> pages = collect(Optional.empty());

    // then
    assertThat(pages.stream().map(p -> p.mutations().size()).toList())
        .containsExactly(1000, 1000, 500);
    assertThat(upserts(pages)).hasSize(2500).doesNotHaveDuplicates();
    assertThat(offsetOf(pages.get(0))).isLessThan(offsetOf(pages.get(1)));
  }

  @Test
  @DisplayName("첫 페이지의 체크포인트에서 이어 받으면 남은 줄만 낸다")
  void resumesFromFirstPageCheckpoint() throws IOException {
    // given
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 2500; i++) sb.append(TranscriptLines.user("m" + i).build()).append("\n");
    write(dir.resolve("s1.jsonl"), sb.toString());
    List<CollectionPage> pages = collect(Optional.empty());

    // when
    List<CollectionPage> rest = collect(Optional.of(pages.get(0).nextCheckpoint()));

    // then
    assertThat(upserts(rest)).hasSize(1500).startsWith("d/s1#1000").endsWith("d/s1#2499");
  }

  private static long offsetOf(CollectionPage page) {
    String v = page.nextCheckpoint().valueJson();
    return Long.parseLong(v.replaceAll(".*\"offset\":(\\d+).*", "$1"));
  }

  @Nested
  @DisplayName("두 파일의 수집 뒤 줄이 늘고 첫 파일을 읽을 수 없으면")
  class UnreadableFile {
    private Optional<Checkpoint> checkpoint;

    @BeforeEach
    void givenUnreadableAppendedFile() throws IOException {
      Path a = dir.resolve("a.jsonl");
      Path b = dir.resolve("b.jsonl");
      write(
          a, TranscriptLines.user("a0").build() + "\n" + TranscriptLines.user("a1").build() + "\n");
      write(b, TranscriptLines.user("b0").build() + "\n");
      List<CollectionPage> pages = collect(Optional.empty());
      if (!upserts(pages).equals(List.of("d/a#0", "d/a#1", "d/b#0")))
        throw new IllegalStateException("given 준비 실패: 두 파일의 원래 줄 수집");
      checkpoint = last(pages);
      append(a, TranscriptLines.user("a2").build() + "\n");
      append(b, TranscriptLines.user("b1").build() + "\n");
      Files.setPosixFilePermissions(a, PosixFilePermissions.fromString("---------"));
    }

    @AfterEach
    void restorePermissions() throws IOException {
      Files.setPosixFilePermissions(
          dir.resolve("a.jsonl"), PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    @DisplayName("읽을 수 있는 형제 파일의 추가 줄만 수집한다")
    void collectsReadableSibling() {
      // when
      List<CollectionPage> pages = collect(checkpoint);
      // then
      assertThat(upserts(pages)).containsExactly("d/b#1");
    }

    @Test
    @DisplayName("읽지 못한 파일은 원문 없이 고정 문구로 거절한다")
    void reportsRejectionWithoutOriginalText() {
      // when
      List<CollectionPage> pages = collect(checkpoint);
      // then
      assertThat(pages.stream().flatMap(p -> p.rejections().stream()))
          .singleElement()
          .extracting(RejectedItem::message)
          .isEqualTo("읽을 수 없는 트랜스크립트 파일");
    }

    @Test
    @DisplayName("권한을 복구하면 읽지 못한 위치의 추가 줄부터 수집한다")
    void resumesUnreadablePosition() throws IOException {
      // given
      Optional<Checkpoint> blocked = givenRejectedCollection();
      restorePermissions();
      // when
      List<CollectionPage> pages = collect(blocked);
      // then
      assertThat(upserts(pages)).containsExactly("d/a#2");
    }

    private Optional<Checkpoint> givenRejectedCollection() {
      List<CollectionPage> pages = collect(checkpoint);
      if (!upserts(pages).equals(List.of("d/b#1"))
          || pages.stream().mapToInt(p -> p.rejections().size()).sum() != 1)
        throw new IllegalStateException("given 준비 실패: 첫 파일 거절과 형제 파일 수집");
      return last(pages);
    }
  }

  @Test
  @DisplayName("마지막 파일을 읽지 못하면 다음 파일 없이도 거절 페이지를 내보낸다")
  void emitsRejectionForLastUnreadableFile() throws IOException {
    // given
    Path file = dir.resolve("s1.jsonl");
    write(file, TranscriptLines.user("합성 질문").build() + "\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    // when
    List<CollectionPage> pages;
    try {
      pages = collect(Optional.empty());
    } finally {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    // then
    assertThat(pages.stream().flatMap(page -> page.rejections().stream()))
        .extracting(RejectedItem::message)
        .containsExactly("읽을 수 없는 트랜스크립트 파일");
  }

  @Test
  @DisplayName("가린 UTF-8 본문이 8MiB에 닿으면 완전한 줄 뒤에서 페이지를 나눈다")
  void splitsPagesAtByteBudget() throws IOException {
    // given
    write(
        dir.resolve("s1.jsonl"),
        TranscriptLines.user("가".repeat(1_400_000)).build()
            + "\n"
            + TranscriptLines.user("나".repeat(1_400_000)).build()
            + "\n"
            + TranscriptLines.user("끝").build()
            + "\n");

    // when
    List<CollectionPage> pages = collect(Optional.empty());

    // then
    assertThat(pages).extracting(p -> p.mutations().size()).containsExactly(2, 1);
  }

  @Test
  @DisplayName("기존 sqlite 체크포인트가 읽은 마지막 줄부터 이어 받는다")
  void resumesLegacySqliteCheckpoint() throws IOException {
    // given
    write(dir.resolve("s1.jsonl"), "{}\n" + TranscriptLines.user("이어 읽기").build() + "\n");
    Optional<Checkpoint> saved =
        scenario.givenStoredCheckpoint(
            root.resolve("state.sqlite"),
            new Checkpoint(
                1,
                "{\"files\":{\"d/s1.jsonl\":{\"offset\":3,\"lines\":1,\"head\":\"44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\"}}}"));

    // when
    List<CollectionPage> pages = collect(saved);

    // then
    assertThat(upserts(pages)).containsExactly("d/s1#1");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"files\":{\"d/s1.jsonl\":{\"head\":\"abc\"}}}",
        "{\"files\":{\"d/s1.jsonl\":{\"offset\":null,\"lines\":null,\"head\":\"abc\"}}}"
      })
  @DisplayName("저장된 위치가 누락되거나 null이면 첫 줄부터 수집한다")
  void resumesLegacyDefaultPosition(String valueJson) throws IOException {
    // given
    write(dir.resolve("s1.jsonl"), TranscriptLines.user("다시 읽기").build() + "\n");
    Optional<Checkpoint> saved =
        scenario.givenStoredCheckpoint(root.resolve("state.sqlite"), new Checkpoint(1, valueJson));

    // when
    List<CollectionPage> pages = collect(saved);

    // then
    assertThat(upserts(pages)).containsExactly("d/s1#0");
  }

  @Test
  @DisplayName("거절된 줄도 페이지의 1000줄 상한에 포함한다")
  void rejectedLinesCountTowardPageBudget() throws IOException {
    // given
    write(
        dir.resolve("s1.jsonl"),
        "broken\n".repeat(1000) + TranscriptLines.user("끝").build() + "\n");

    // when
    List<CollectionPage> pages = collect(Optional.empty());

    // then
    assertThat(pages).extracting(p -> p.rejections().size()).containsExactly(1000, 0);
    assertThat(upserts(pages)).containsExactly("d/s1#1000");
  }
}
