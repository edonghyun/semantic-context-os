package io.scos.server;

import static io.scos.server.TestMcpHttpServer.SINGLE_USER;
import static io.scos.server.TestMcpHttpServer.STORED_DOCUMENT;
import static io.scos.server.TestMcpHttpServer.STORED_TITLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.scos.boot.AccessProperties;
import io.scos.boot.AccessProperties.Mode;
import io.scos.engine.ContextEngine;
import io.scos.mcp.ErrorExposure;
import io.scos.mcp.McpToolSpecs;
import io.scos.mcp.McpTools;
import io.scos.mcp.QueryLog;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("데몬 HTTP MCP 엔드포인트는 MCP 클라이언트와 HTTP로 왕복하고, 닫을 때 소유한 자원을 닫는다")
class McpHttpEndpointTest {
  static final String MISSING_DOCUMENT = "no-such-document";

  static TestMcpHttpServer server;

  @BeforeAll
  static void start(@TempDir Path baseDir) throws Exception {
    server = TestMcpHttpServer.start(baseDir);
  }

  @AfterAll
  static void stop() throws Exception {
    server.close();
  }

  @Test
  @DisplayName("search_context로 저장된 문서를 찾는다")
  void findsStoredDocument() {
    // when
    List<String> found = server.searchDocumentIds(STORED_TITLE);

    // then
    assertThat(found).containsExactly(STORED_DOCUMENT);
  }

  @Test
  @DisplayName("initialize 응답에 사용 안내를 싣는다")
  void answersInitializeWithInstructions() {
    // when
    String instructions = server.instructions();

    // then
    assertThat(instructions).contains("assemble_context");
  }

  @Test
  @DisplayName("계약 위반은 HTTP로도 isError로 돌아온다")
  void contractViolationIsToolError() {
    // when
    CallToolResult result = server.call("read_document", Map.of("documentId", MISSING_DOCUMENT));

    // then
    assertThat(result.isError()).isTrue();
  }

  @Test
  @DisplayName("닫으면 넘겨받은 자원을 닫는다")
  void closeReleasesResources() throws Exception {
    // given
    AtomicBoolean released = new AtomicBoolean();
    McpHttpEndpoint endpoint =
        McpHttpEndpoint.create(
            memoryTools(),
            McpHttpConfiguration.PATH,
            ErrorExposure.RAW,
            McpHttpConfiguration.contextExtractor(SINGLE_USER),
            () -> released.set(true));

    // when
    endpoint.close();

    // then
    assertThat(released).isTrue();
  }

  @Test
  @DisplayName("단일 사용자 HTTP는 필터 없이도 인증된 도구 응답을 낸다")
  void singleUserNeedsNoPrincipalFilter() {
    // when
    CallToolResult result = server.call("read_document", Map.of("documentId", STORED_DOCUMENT));

    // then
    assertThat(result.isError()).isNotEqualTo(true);
  }

  @Test
  @DisplayName("MASKED HTTP 예외는 원문 없는 도구 오류로 직렬화한다")
  void masksExceptionOnWire() throws Exception {
    // given
    try (var failure =
        new McpHttpFailureScenario(
            new IllegalStateException("SELECT body FROM secret-doc"), ErrorExposure.MASKED)) {
      // when
      JsonNode wire = failure.call();

      // then
      assertThat(wire.path("result").path("isError").asBoolean()).isTrue();
      assertThat(wire.path("result").path("content").get(0).path("text").asString())
          .matches("내부 오류가 발생했습니다\\(참조 [0-9a-f]{8}-[0-9a-f]{3}\\)");
      assertThat(wire.toString()).doesNotContain("SELECT", "secret-doc");
    }
  }

  static Stream<Error> handlerErrors() {
    return Stream.of(
        new Error("secret-doc", new RuntimeException("secret-cause")),
        new StackOverflowError("secret-doc"));
  }

  @ParameterizedTest
  @MethodSource("handlerErrors")
  @DisplayName("MASKED HTTP Error는 원문 없는 JSON-RPC 오류로 직렬화한다")
  void masksErrorOnWire(Error error) throws Exception {
    // given
    try (var failure = new McpHttpFailureScenario(error, ErrorExposure.MASKED)) {
      // when
      JsonNode wire = failure.call();

      // then
      assertThat(wire.path("error").path("message").asString())
          .matches("내부 오류가 발생했습니다\\(참조 [0-9a-f]{8}-[0-9a-f]{3}\\)");
      assertThat(wire.toString())
          .doesNotContain("secret-doc", "secret-cause", "suppressed", "cause");
    }
  }

  @ParameterizedTest
  @CsvSource({"SINGLE_USER, RAW", "MULTI_USER, MASKED"})
  @DisplayName("HTTP 오류 노출 정책은 접근 모드를 따른다")
  void exposureFollowsAccessMode(Mode mode, ErrorExposure expected) {
    // given
    var access = new AccessProperties(mode, null, null, null);

    // when
    ErrorExposure exposure = McpHttpConfiguration.errorExposure(access);

    // then
    assertThat(exposure).isEqualTo(expected);
  }

  @Test
  @DisplayName("단일 사용자 추출기는 요청 속성의 사용자와 무관하게 local을 싣는다")
  void singleUserExtractorIgnoresRequestAttributes() {
    // given
    var request = new MockHttpServletRequest();
    request.setAttribute("io.scos.server.principal", new Principal("alice"));

    // when
    var context = McpHttpConfiguration.contextExtractor(SINGLE_USER).extract(request);

    // then
    assertThat(context.get(McpToolSpecs.PRINCIPAL_KEY)).isEqualTo(new Principal("local"));
  }

  @Test
  @DisplayName("다중 사용자 추출기는 요청 속성의 principal을 싣는다")
  void multiUserExtractorCarriesPrincipal() {
    // given
    var request = new MockHttpServletRequest();
    request.setAttribute("io.scos.server.principal", new Principal("alice"));
    var access = new AccessProperties(Mode.MULTI_USER, null, null, null);

    // when
    var context = McpHttpConfiguration.contextExtractor(access).extract(request);

    // then
    assertThat(context.get(McpToolSpecs.PRINCIPAL_KEY)).isEqualTo(new Principal("alice"));
  }

  @Test
  @DisplayName("다중 사용자 추출기는 Principal이 아닌 요청 속성을 인증 문맥에 싣지 않는다")
  void multiUserExtractorRejectsWrongAttributeType() {
    // given
    var request = new MockHttpServletRequest();
    request.setAttribute(PrincipalBearerFilter.PRINCIPAL_ATTRIBUTE, "alice");
    var access = new AccessProperties(Mode.MULTI_USER, null, null, null);

    // when
    var context = McpHttpConfiguration.contextExtractor(access).extract(request);

    // then
    assertThat(context.get(McpToolSpecs.PRINCIPAL_KEY)).isNull();
  }

  @Test
  @DisplayName("두 사용자 토큰은 각각 자기 문서만 도구 응답으로 받는다")
  void tokensReachRestrictedEngine() throws Exception {
    try (var http = new McpHttpPrincipalScenario()) {
      var documents = http.documentsForBothTokens();

      // then
      assertThat(documents).containsExactly(List.of("alice-guide"), List.of("bob-guide"));
    }
  }

  @Test
  @DisplayName("다중 사용자 요청은 필터가 없으면 인증 정보 도구 오류다")
  void multiUserWithoutFilterIsToolError() throws Exception {
    try (var http = new McpHttpPrincipalScenario()) {
      var wire = http.callWithoutFilter();

      // then
      assertThat(wire.path("result").path("isError").asBoolean()).isTrue();
      assertThat(wire.path("result").path("content").get(0).path("text").asString())
          .isEqualTo("인증 정보가 없습니다");
    }
  }

  static McpTools memoryTools() {
    var units = new MemoryUnitStore();
    var vectors = new MemoryVectorIndex();
    return new McpTools(
        ContextEngine.builder()
            .units(units)
            .unrestricted(new MemoryUnitMaintenance(units, vectors))
            .keywords(new MemoryKeywordIndex())
            .vectors(vectors)
            .embedder(FixedEmbedder.zero(2))
            .build(),
        JsonMapper.builder().build(),
        QueryLog.NONE);
  }
}
