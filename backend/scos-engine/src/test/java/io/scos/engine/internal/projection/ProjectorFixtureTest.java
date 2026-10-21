package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.groups.Tuple.tuple;

import io.scos.engine.internal.projection.ProjectorFixture.DocView;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 공용 {@link ProjectorFixture}가 TS 원본 항목을 읽는 규칙: 순서 스킴(Slack)과 공급자 생성·수정 시각(Tiro 등)은 원본에 있을 때만 읽는다.
 * 원본 필드를 문서에 그대로 비추는 대역 투영기로 본다. 기존 Slack fixture(p4)는 순서 스킴만 가진 그대로다.
 */
@DisplayName("투영기 fixture 비교 도구는 TS 원본의 순서·시각·blob을 있는 그대로 읽는다(§3.6)")
class ProjectorFixtureTest {
  static final Path P4 =
      Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"));
  static final String NO_ORDER = "none";
  static final String NO_TIME = "-";
  static final String CREATED = "2026-10-01T01:00:00Z";
  static final String UPDATED = "2026-10-03T09:00:00Z";

  /** 원본 하나가 문서 하나. 제목 = 순서 스킴(없으면 none), 본문 = 읽은 원본 본문, 시각 = 원본의 공급자 시각(없으면 -). */
  static final class EchoProjector implements Projector {
    @Override
    public String kind() {
      return "echo";
    }

    @Override
    public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
      Map<String, List<RawItem>> groups = new TreeMap<>();
      for (RawItem item : items) groups.put(item.externalId(), List.of(item));
      return groups;
    }

    @Override
    public Optional<SourceDocument> project(
        String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
      RawItem item = items.getFirst();
      return Optional.of(
          new SourceDocument(
              sourceId,
              documentId,
              "echo://" + documentId,
              item.sourceOrder().map(SourceOrder::scheme).orElse(NO_ORDER),
              reader.apply(item).orElse(""),
              item.sourceCreatedAt().orElse(NO_TIME),
              item.sourceUpdatedAt().orElse(NO_TIME)));
    }
  }

  static ProjectorFixture over(Path dir) {
    return ProjectorFixture.of(dir, new EchoProjector(), "echo");
  }

  @Nested
  @DisplayName("Slack fixture(p4) 원본은")
  class SlackFixture {
    @Test
    @DisplayName("순서 스킴을 그대로 읽는다")
    void readsOrderScheme() {
      // when
      List<DocView> documents = over(P4).documents();

      // then
      assertThat(documents)
          .isNotEmpty()
          .extracting(DocView::title)
          .containsOnly("slack.message-state.v1");
    }

    @Test
    @DisplayName("공급자 시각이 없으니 비워 둔다")
    void leavesProviderTimesEmpty() {
      // when
      List<DocView> documents = over(P4).documents();

      // then
      assertThat(documents)
          .isNotEmpty()
          .extracting(DocView::createdAt, DocView::updatedAt)
          .containsOnly(tuple(NO_TIME, NO_TIME));
    }
  }

  @Nested
  @DisplayName("blob으로 저장된 원본은")
  class BlobItem {
    static final String BLOB_KEY = "sha256/" + "a".repeat(64);
    static final String BLOB_BODY = "{\"큰\":\"원본\"}";

    @TempDir Path dir;

    void givenBlobItem() throws IOException {
      Files.writeString(
          dir.resolve("expected-raw-items.json"),
          "{\"items\":[{\"key\":{\"externalId\":\"page-big\"},\"resourceType\":\"notion.page\","
              + "\"sourceVersion\":\"v1\",\"observedAt\":\"2026-10-15T00:00:00.000Z\",\"metadata\":{},"
              + "\"content\":{\"storage\":\"blob\",\"mediaType\":\"application/json\",\"blobKey\":\""
              + BLOB_KEY
              + "\"}}],\"jsonBlobs\":{\""
              + BLOB_KEY
              + "\":\"{\\\"큰\\\":\\\"원본\\\"}\"}}");
    }

    @Test
    @DisplayName("JSON 원본인데 jsonBlobs에 내용이 없으면 실패한다")
    void failsWhenJsonBlobMissing() throws IOException {
      // given
      Files.writeString(
          dir.resolve("expected-raw-items.json"),
          "{\"items\":[{\"key\":{\"externalId\":\"page-big\"},\"resourceType\":\"notion.page\","
              + "\"sourceVersion\":\"v1\",\"observedAt\":\"2026-10-15T00:00:00.000Z\",\"metadata\":{},"
              + "\"content\":{\"storage\":\"blob\",\"mediaType\":\"application/json\",\"blobKey\":\""
              + BLOB_KEY
              + "\"}}]}");

      // when
      Throwable thrown = catchThrowable(() -> over(dir).documents());

      // then
      assertThat(thrown).hasMessageContaining(BLOB_KEY);
    }

    @Test
    @DisplayName("본문을 TS가 남긴 jsonBlobs 내용으로 읽는다")
    void readsBodyFromJsonBlobs() throws IOException {
      // given
      givenBlobItem();

      // when
      List<DocView> documents = over(dir).documents();

      // then
      assertThat(documents).extracting(DocView::body).containsExactly(BLOB_BODY);
    }
  }

  @Nested
  @DisplayName("순서 스킴과 공급자 시각이 JSON null인 원본은")
  class NullFields {
    @TempDir Path dir;

    void givenItemWithNullFields() throws IOException {
      Files.writeString(
          dir.resolve("expected-raw-items.json"),
          "{\"items\":[{\"key\":{\"externalId\":\"note:n1\"},\"resourceType\":\"tiro.note\","
              + "\"sourceVersion\":\"v1\",\"sourceOrder\":null,\"sourceCreatedAt\":null,\"sourceUpdatedAt\":null,"
              + "\"observedAt\":\"2026-10-15T00:00:00.000Z\",\"metadata\":{},"
              + "\"content\":{\"storage\":\"inline\",\"mediaType\":\"application/json\",\"value\":{}}}]}");
    }

    @Test
    @DisplayName("없는 것으로 읽는다")
    void readsNullAsAbsent() throws IOException {
      // given
      givenItemWithNullFields();

      // when
      List<DocView> documents = over(dir).documents();

      // then
      assertThat(documents)
          .extracting(DocView::title, DocView::createdAt, DocView::updatedAt)
          .containsExactly(tuple(NO_ORDER, NO_TIME, NO_TIME));
    }
  }

  @Nested
  @DisplayName("순서 스킴 없이 공급자 시각만 가진 원본은")
  class ProviderTimesOnly {
    @TempDir Path dir;

    @BeforeEach
    void givenItemWithProviderTimes() throws IOException {
      Files.writeString(
          dir.resolve("expected-raw-items.json"),
          "{\"items\":[{\"key\":{\"externalId\":\"note:n1\"},\"resourceType\":\"tiro.note\","
              + "\"sourceVersion\":\"v1\",\"sourceCreatedAt\":\""
              + CREATED
              + "\",\"sourceUpdatedAt\":\""
              + UPDATED
              + "\",\"observedAt\":\"2026-10-15T00:00:00.000Z\",\"metadata\":{},"
              + "\"content\":{\"storage\":\"inline\",\"mediaType\":\"application/json\",\"value\":{}}}]}");
    }

    @Test
    @DisplayName("순서 스킴 없이 읽는다")
    void readsWithoutOrder() throws IOException {
      // when
      List<DocView> documents = over(dir).documents();

      // then
      assertThat(documents).extracting(DocView::title).containsExactly(NO_ORDER);
    }

    @Test
    @DisplayName("공급자 생성·수정 시각을 읽는다")
    void readsProviderTimes() throws IOException {
      // when
      List<DocView> documents = over(dir).documents();

      // then
      assertThat(documents)
          .extracting(DocView::createdAt, DocView::updatedAt)
          .containsExactly(tuple(CREATED, UPDATED));
    }
  }
}
