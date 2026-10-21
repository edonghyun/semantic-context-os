package io.scos.connector.googledrive;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.SourceDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drive 투영기의 경계(TS GoogleDriveProjector, expected-projector-cases.json): 객체·배열·문자열이 아닌 값의 관대한 읽기,
 * JS 객체 순서· trim·수 표기, 시트 범위 버전 불일치·빠진 범위, 발표자 노트 id 일치(없음끼리 포함), 댓글·답글 삭제, 수집 제한의 `String()`. 사례마다
 * 원본을 묶고 투영한 문서가 TS와 같다.
 */
@DisplayName("Drive 투영기는 경계 사례에서 TS와 같은 문서를 낸다(F7)")
class GoogleDriveProjectorTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path FIXTURES =
      Path.of(
          System.getProperty("scos.fixtures.google-drive.dir", "../harness/fixtures/google-drive"));

  /** 문서 하나의 비교 값(건너뛴 문서는 skipped만). */
  record Doc(
      String documentId,
      boolean skipped,
      String sourceId,
      String uri,
      String title,
      String body,
      String createdAt,
      String updatedAt) {
    static Doc of(String documentId, Optional<SourceDocument> d) {
      return d.map(
              x ->
                  new Doc(
                      documentId,
                      false,
                      x.sourceId(),
                      x.uri(),
                      x.title(),
                      x.body(),
                      x.createdAt(),
                      x.updatedAt()))
          .orElse(new Doc(documentId, true, null, null, null, null, null, null));
    }

    static Doc expected(JsonNode e) {
      if (e.path("skipped").asBoolean(false))
        return new Doc(e.get("documentId").asString(), true, null, null, null, null, null, null);
      return new Doc(
          e.get("documentId").asString(),
          false,
          e.get("sourceId").asString(),
          e.get("uri").asString(),
          e.get("title").asString(),
          e.get("body").asString(),
          e.get("createdAt").asString(),
          e.get("updatedAt").asString());
    }
  }

  static Stream<Arguments> cases() {
    try {
      JsonNode root =
          JSON.readTree(Files.readString(FIXTURES.resolve("expected-projector-cases.json")));
      return StreamSupport.stream(root.get("cases").spliterator(), false)
          .map(c -> Arguments.of(c.get("label").asString(), c));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static RawItem item(JsonNode n) {
    return new RawItem(
        n.get("key").get("externalId").asString(),
        n.get("resourceType").asString(),
        n.get("sourceVersion").asString(),
        Optional.empty(),
        Optional.ofNullable(n.get("sourceCreatedAt")).map(JsonNode::asString),
        Optional.ofNullable(n.get("sourceUpdatedAt")).map(JsonNode::asString),
        Instant.parse(n.get("observedAt").asString()),
        n.get("metadata").toString(),
        Optional.of(
            new RawContent.Inline("application/json", n.get("content").get("value").toString())));
  }

  static List<Doc> project(JsonNode c) {
    GoogleDriveProjector projector = new GoogleDriveProjector(JSON);
    List<RawItem> items = new ArrayList<>();
    c.get("items").forEach(n -> items.add(item(n)));
    List<Doc> out = new ArrayList<>();
    for (Map.Entry<String, List<RawItem>> e : projector.groupIntoDocuments(items).entrySet())
      out.add(
          Doc.of(
              e.getKey(),
              projector.project(
                  "google-drive-synthetic",
                  e.getKey(),
                  e.getValue(),
                  i -> i.content().map(r -> ((RawContent.Inline) r).valueJson()))));
    return out;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("TS와 같은 문서를 낸다")
  void projectsLikeTs(String label, JsonNode c) {
    // when
    List<Doc> documents = project(c);

    // then
    assertThat(documents)
        .containsExactlyElementsOf(
            StreamSupport.stream(c.get("documents").spliterator(), false)
                .map(Doc::expected)
                .toList());
  }

  @Test
  @DisplayName("google-drive 소스를 맡는다")
  void projectsGoogleDriveKind() {
    // when·then
    assertThat(new GoogleDriveProjector(JSON).kind()).isEqualTo("google-drive");
  }
}
