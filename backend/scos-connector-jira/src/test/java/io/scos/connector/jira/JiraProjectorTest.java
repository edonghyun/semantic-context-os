package io.scos.connector.jira;

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
 * Jira 투영기(TS source-projector/src/jira-projector.ts)의 경계 사례: 데이터셋 수집이 만들지 않는 모양(key 없음·수·null,
 * fields가 없거나 배열, 내용이 객체 아님, metadata.url 없음, 공급자 시각 없음, 이름 대체, names 값, 건너뛰는 필드, 첨부 모양, 이슈 상세 섹션)과
 * 섞인 resourceType의 문서 묶기. 기대값은 TS JiraProjector가 만든
 * harness/fixtures/jira/expected-projector-cases.json이다.
 */
@DisplayName("Jira 투영기는 경계 사례에서 TS와 같은 문서를 낸다(F5)")
class JiraProjectorTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path DIR = JiraAssembly.FIXTURES;
  static final String SOURCE_ID = JiraAssembly.SOURCE.value();

  final JiraProjector projector = new JiraProjector(JSON);

  static JsonNode golden() {
    try {
      return JSON.readTree(Files.readString(DIR.resolve("expected-projector-cases.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** TS 원본 항목 JSON → RawItem(내용은 inline JSON). */
  static RawItem rawItem(JsonNode n) {
    return new RawItem(
        n.get("key").get("externalId").asString(),
        n.get("resourceType").asString(),
        n.get("sourceVersion").asString(),
        Optional.empty(),
        optionalString(n.get("sourceCreatedAt")),
        optionalString(n.get("sourceUpdatedAt")),
        Instant.parse(n.get("observedAt").asString()),
        JSON.writeValueAsString(n.get("metadata")),
        Optional.of(
            new RawContent.Inline(
                n.get("content").get("mediaType").asString(),
                JSON.writeValueAsString(n.get("content").get("value")))));
  }

  static Optional<String> optionalString(JsonNode value) {
    return value == null || value.isNull() ? Optional.empty() : Optional.of(value.asString());
  }

  /** 비교용 문서 값 — TS 기대의 document 필드와 같은 모양(없으면 null). */
  static JsonNode view(Optional<SourceDocument> document) {
    return document
        .<JsonNode>map(
            d ->
                JSON.createObjectNode()
                    .put("documentId", d.documentId())
                    .put("sourceId", d.sourceId())
                    .put("uri", d.uri())
                    .put("title", d.title())
                    .put("body", d.body())
                    .put("createdAt", d.createdAt())
                    .put("updatedAt", d.updatedAt()))
        .orElse(JSON.nullNode());
  }

  static Stream<Arguments> cases() {
    return StreamSupport.stream(golden().get("cases").spliterator(), false)
        .map(c -> Arguments.of(c.get("label").asString(), c.get("item"), c.get("document")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("원본 하나를 TS와 같은 문서로 투영하거나 TS처럼 건너뛴다")
  void projectsLikeTs(String label, JsonNode item, JsonNode expected) {
    // given
    RawItem raw = rawItem(item);

    // when
    Optional<SourceDocument> document =
        projector.project(SOURCE_ID, raw.externalId(), List.of(raw), JiraProjectorTest::inline);

    // then
    assertThat(view(document)).isEqualTo(expected);
  }

  @Test
  @DisplayName("jira. 원본 가운데 첨부·필드를 뺀 것만 원본마다 문서로 묶는다")
  void groupsLikeTs() {
    // given
    JsonNode grouping = golden().get("grouping");
    List<RawItem> items = new ArrayList<>();
    for (JsonNode type : grouping.get("resourceTypes"))
      items.add(itemOfType(type.asString(), items.size()));

    // when
    List<String> documentIds = List.copyOf(projector.groupIntoDocuments(items).keySet());

    // then
    assertThat(documentIds)
        .containsExactlyElementsOf(
            StreamSupport.stream(grouping.get("documentIds").spliterator(), false)
                .map(JsonNode::asString)
                .toList());
  }

  @Test
  @DisplayName("같은 외부 id 원본이 둘이면 TS처럼 나중 것으로 투영한다")
  void projectsLastOfDuplicateExternalIds() {
    // given
    JsonNode twins = golden().get("duplicateExternalId");
    List<RawItem> items = new ArrayList<>();
    twins.get("items").forEach(i -> items.add(rawItem(i)));
    Map<String, List<RawItem>> groups = projector.groupIntoDocuments(items);
    String documentId = items.getFirst().externalId();

    // when
    Optional<SourceDocument> document =
        projector.project(SOURCE_ID, documentId, groups.get(documentId), JiraProjectorTest::inline);

    // then
    assertThat(document.map(SourceDocument::title)).contains(twins.get("title").asString());
  }

  /** 이 resourceType의 원본 하나(TS 묶기 사례와 같은 외부 id `<type>:<n>`). */
  static RawItem itemOfType(String resourceType, int n) {
    return new RawItem(
        resourceType + ":" + n,
        resourceType,
        "v",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Instant.EPOCH,
        "{}",
        Optional.empty());
  }

  static Optional<String> inline(RawItem item) {
    return item.content().map(c -> ((RawContent.Inline) c).valueJson());
  }
}
