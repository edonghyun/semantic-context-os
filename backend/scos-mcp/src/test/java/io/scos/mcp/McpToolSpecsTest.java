package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.scos.engine.ContextEngine;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("MCP 도구 명세는 한 벌이고, 계약 위반은 isError로·인프라 실패는 예외로 낸다")
class McpToolSpecsTest {
  static final int EMBEDDING_DIM = 2;
  static final RuntimeException EMBEDDER_DOWN = new IllegalStateException("embedder down");
  static final String MISSING_DOCUMENT = "no-such-document";

  /** 사용 안내 길이 상한(문자) — 모든 프로젝트의 세션 프롬프트에 실리는 비용의 상한으로 이 저장소가 정한 값이다. */
  static final int INSTRUCTIONS_CEILING = 2000;

  static McpTools tools(Embedder embedder) {
    var units = new MemoryUnitStore();
    var vectors = new MemoryVectorIndex();
    return new McpTools(
        ContextEngine.builder()
            .units(units)
            .unrestricted(new MemoryUnitMaintenance(units, vectors))
            .keywords(new MemoryKeywordIndex())
            .vectors(vectors)
            .embedder(embedder)
            .build(),
        JsonMapper.builder().build(),
        QueryLog.NONE);
  }

  static final McpTools TOOLS = tools(FixedEmbedder.zero(EMBEDDING_DIM));

  /** 상태 없는 명세로 도구 하나를 호출한다(HTTP 전송이 하는 일과 같다). */
  static CallToolResult call(McpTools tools, String name, Map<String, Object> arguments) {
    return new McpToolSpecs(tools)
        .stateless(ErrorExposure.RAW).stream()
            .filter(spec -> spec.tool().name().equals(name))
            .findFirst()
            .orElseThrow()
            .callHandler()
            .apply(
                McpTransportContext.create(
                    Map.of(McpToolSpecs.PRINCIPAL_KEY, Principal.singleUser())),
                new CallToolRequest(name, arguments));
  }

  static List<Tool> syncTools() {
    return McpToolSpecs.sync(TOOLS).stream()
        .map(McpServerFeatures.SyncToolSpecification::tool)
        .toList();
  }

  static List<Tool> statelessTools() {
    return new McpToolSpecs(TOOLS)
        .stateless(ErrorExposure.RAW).stream()
            .map(McpStatelessServerFeatures.SyncToolSpecification::tool)
            .toList();
  }

  /** tools/list에 실리는 도구 하나의 설명. */
  static String description(String tool) {
    return syncTools().stream()
        .filter(t -> t.name().equals(tool))
        .findFirst()
        .orElseThrow()
        .description();
  }

  /** 모든 호출에서 {@link #EMBEDDER_DOWN}을 던지는 임베더 — 인프라 실패 대역. */
  static Embedder failingEmbedder() {
    return new Embedder() {
      @Override
      public EmbedderIdentity identity() {
        return FixedEmbedder.zero(EMBEDDING_DIM).identity();
      }

      @Override
      public List<double[]> embed(List<String> texts) {
        throw EMBEDDER_DOWN;
      }
    };
  }

  @ParameterizedTest
  @EnumSource(ErrorExposure.class)
  @DisplayName("정상 도구 호출은 모드와 관계없이 null이 아닌 성공 결과를 반환한다")
  void successfulCallHasResult(ErrorExposure exposure) {
    // given
    var scenario = McpFailureScenario.healthy();

    // when
    CallToolResult result = scenario.call(exposure);

    // then
    assertThat(result).isNotNull();
    assertThat(result.isError()).isNotEqualTo(true);
  }

  @ParameterizedTest
  @EnumSource(ErrorExposure.class)
  @DisplayName("정상 도구 호출은 모드와 관계없이 비어 있지 않은 텍스트 본문 하나를 반환한다")
  void successfulCallHasText(ErrorExposure exposure) {
    // given
    var scenario = McpFailureScenario.healthy();

    // when
    CallToolResult result = scenario.call(exposure);

    // then
    assertThat(result.content())
        .singleElement()
        .isInstanceOfSatisfying(
            TextContent.class, content -> assertThat(content.text()).isNotBlank());
  }

  @Test
  @DisplayName("도구는 검색·읽기·관련 문서·조립 넷이다")
  void fourTools() {
    // then
    assertThat(syncTools())
        .extracting(Tool::name)
        .containsExactly(
            "search_context", "read_document", "related_documents", "assemble_context");
  }

  @Test
  @DisplayName("stdio와 HTTP는 같은 이름·설명·입력 스키마를 낸다")
  void sameSpecsOnBothTransports() {
    // then
    assertThat(statelessTools()).isEqualTo(syncTools());
  }

  @Test
  @DisplayName("서버 사용 안내는 네 도구를 모두 이름으로 부른다")
  void instructionsNameEveryTool() {
    // then
    assertThat(McpToolSpecs.INSTRUCTIONS)
        .contains("search_context", "read_document", "related_documents", "assemble_context");
  }

  @Test
  @DisplayName("서버 사용 안내는 모든 세션 프롬프트에 실리므로 2,000자를 넘지 않는다")
  void instructionsStayShort() {
    // then
    assertThat(McpToolSpecs.INSTRUCTIONS).hasSizeLessThanOrEqualTo(INSTRUCTIONS_CEILING);
  }

  @Test
  @DisplayName("조립 도구 설명은 모으는 출처를 이름으로 말한다")
  void assembleDescriptionNamesSources() {
    // when
    String description = description("assemble_context");

    // then
    assertThat(description).contains("Slack", "Notion", "Jira", "Google Drive");
  }

  @Test
  @DisplayName("검색 도구 설명은 본문까지 한 번에 모으는 조립 도구를 가리킨다")
  void searchDescriptionPointsToAssemble() {
    // when
    String description = description("search_context");

    // then
    assertThat(description).contains("assemble_context");
  }

  @Test
  @DisplayName("검색·조립 스키마는 effort·profile 선택지를 노출한다")
  void searchAndAssembleSchemasExposeOptions() {
    // then
    assertThat(List.of(McpToolSpecs.SEARCH_SCHEMA, McpToolSpecs.ASSEMBLE_SCHEMA))
        .allSatisfy(
            schema ->
                assertThat(schema)
                    .contains(
                        "\"effort\":{\"type\":\"string\",\"enum\":[\"low\",\"medium\",\"high\"]",
                        "\"profile\":{\"type\":\"string\",\"enum\":[\"auto\",\"current\"]"));
  }

  @Test
  @DisplayName("검색·조립 입력 스키마는 current가 관련 결과를 최신순으로 반환한다고 설명한다")
  void schemasExplainCurrent() {
    // then
    assertThat(List.of(McpToolSpecs.SEARCH_SCHEMA, McpToolSpecs.ASSEMBLE_SCHEMA))
        .allSatisfy(
            schema ->
                assertThat(schema)
                    .contains(
                        "\"profile\":{\"type\":\"string\",\"enum\":[\"auto\",\"current\"],\"description\":\"우선순위. auto=질의로 판단, current=관련 결과를 최신순으로\"}"));
  }

  @Test
  @DisplayName("검색·조립 설명은 effort를 언제 쓰는지 말한다")
  void descriptionsExplainEffort() {
    // then
    assertThat(List.of(description("search_context"), description("assemble_context")))
        .allSatisfy(d -> assertThat(d).contains("effort", "low", "high"));
  }

  @Test
  @DisplayName("관련 문서 설명은 documentId를 주는 검색·조립 도구를 가리킨다")
  void relatedDescriptionNamesDocumentIdSources() {
    // when
    String description = description("related_documents");

    // then
    assertThat(description).contains("search_context", "assemble_context");
  }

  @Test
  @DisplayName("없는 문서를 읽는 계약 위반은 isError와 이유로 돌아온다")
  void contractViolationIsToolError() {
    // when
    CallToolResult result = call(TOOLS, "read_document", Map.of("documentId", MISSING_DOCUMENT));

    // then
    assertThat(result.isError()).isTrue();
    assertThat(((TextContent) result.content().getFirst()).text()).contains(MISSING_DOCUMENT);
  }

  @Test
  @DisplayName("임베더 같은 인프라 실패는 isError로 숨기지 않고 전파한다")
  void infrastructureFailurePropagates() {
    // given
    McpTools failing = tools(failingEmbedder());

    // then
    assertThatThrownBy(() -> call(failing, "search_context", Map.of("query", "배포")))
        .isSameAs(EMBEDDER_DOWN);
  }

  @Test
  @DisplayName("서버 안내는 바뀌는 값을 물을 때 current를 고르게 한다")
  void instructionsExplainCurrent() {
    // then
    assertThat(McpToolSpecs.INSTRUCTIONS)
        .contains("계정", "비밀번호", "주소", "설정값", "담당자", "profile=current", "관련 결과를 최신순으로");
  }

  @ParameterizedTest
  @ValueSource(strings = {"search_context", "assemble_context"})
  @DisplayName("검색과 조립 설명은 바뀌는 값의 current 최신순 조회를 안내한다")
  void descriptionsExplainCurrent(String tool) {
    // when
    String description = description(tool);
    // then
    assertThat(description).contains("계정", "profile=current", "관련 결과를 최신순으로");
  }
}
