package io.scos.mcp;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.ContextEngine;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.TestExecutors;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("원천 id 대체를 고르면 분류할 수 없는 문서는 네 도구 모두 원천 id를 싣는다")
class McpSourceLabelTest {
  static final SpaceId S = new SpaceId("legacy");
  static final ObjectMapper JSON = new ObjectMapper();
  final MemoryUnitStore units = new MemoryUnitStore();
  final MemoryKeywordIndex keywords = new MemoryKeywordIndex();
  final MemoryUnitMaintenance maintenance =
      new MemoryUnitMaintenance(units, new MemoryVectorIndex());
  final ContextEngine engine;
  final McpTools tools;

  McpSourceLabelTest() {
    load(
        unit("notice-1")
            .inDocument("notice")
            .titled("점검 공지")
            .content("오늘 정기 점검")
            .uri("https://board.example/notice/1")
            .source("board-a")
            .linksTo("patch")
            .build(),
        unit("patch-1")
            .inDocument("patch")
            .titled("패치 안내")
            .content("점검 뒤 패치")
            .uri("https://board.example/notice/2")
            .source("board-b")
            .build());
    engine =
        ContextEngine.builder()
            .units(units)
            .keywords(keywords)
            .unrestricted(maintenance)
            .keywordOnly()
            .defaultSpace(S)
            .lexicalExecutor(TestExecutors.CALLER_THREAD)
            .build();
    tools = new McpTools(engine, JSON, QueryLog.NONE, SourceLabels.SOURCE_ID_FALLBACK);
  }

  private void load(ContextUnit... list) {
    maintenance.upsert(S, List.of(list));
    keywords.upsert(
        S,
        List.of(list).stream()
            .map(
                u ->
                    new IndexedText(
                        u.id(), u.documentId(), u.title(), u.content(), u.partition(), false))
            .toList());
  }

  @Test
  @DisplayName("search_context hit마다 출처는 자기 문서의 원천 id다")
  void searchUsesSourceId() {
    // when
    JsonNode hits =
        JSON.readTree(tools.searchContext(Principal.singleUser(), Map.of("query", "정기")))
            .get("hits");

    // then
    assertThat(sourcesByDocument(hits)).containsExactly("notice ← board-a", "patch ← board-b");
  }

  @Test
  @DisplayName("read_document의 출처는 원천 id다")
  void readUsesSourceId() {
    // when
    JsonNode page =
        JSON.readTree(tools.readDocument(Principal.singleUser(), Map.of("documentId", "notice")));

    // then
    assertThat(page.get("source").asString()).isEqualTo("board-a");
  }

  @Test
  @DisplayName("related_documents의 출처는 이웃 문서의 원천 id다")
  void relatedUsesNeighborSourceId() {
    // when
    JsonNode related =
        JSON.readTree(
                tools.relatedDocuments(Principal.singleUser(), Map.of("documentId", "notice")))
            .get("related");

    // then
    assertThat(sourcesByDocument(related)).containsExactly("patch ← board-b");
  }

  @Test
  @DisplayName("assemble_context 유닛마다 출처는 자기 문서의 원천 id다")
  void assembleUsesSourceId() {
    // when
    JsonNode assembled =
        JSON.readTree(tools.assembleContext(Principal.singleUser(), Map.of("query", "정기")))
            .get("units");

    // then
    assertThat(sourcesByDocument(assembled))
        .containsExactlyInAnyOrder("notice ← board-a", "patch ← board-b");
  }

  @Test
  @DisplayName("규칙을 고르지 않은 도구는 원천 id가 있어도 unknown이다")
  void defaultToolsKeepUnknown() {
    // given
    var uriOnly = new McpTools(engine, JSON, QueryLog.NONE);

    // when
    JsonNode hits =
        JSON.readTree(uriOnly.searchContext(Principal.singleUser(), Map.of("query", "정기")))
            .get("hits");

    // then
    assertThat(sourcesByDocument(hits)).containsExactly("notice ← unknown", "patch ← unknown");
  }

  private static List<String> sourcesByDocument(JsonNode items) {
    return StreamSupport.stream(items.spliterator(), false)
        .map(i -> i.get("documentId").asString() + " ← " + i.get("source").asString())
        .toList();
  }
}
