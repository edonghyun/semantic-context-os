package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("MCP는 전송이 정한 요청자로 엔진을 호출한다")
class McpPrincipalTest {
  private final McpPrincipalScenario scenario = new McpPrincipalScenario();

  @ParameterizedTest
  @ValueSource(
      strings = {"search_context", "read_document", "related_documents", "assemble_context"})
  @DisplayName("stateless 도구는 전송 문맥의 사용자 문서만 내보낸다")
  void statelessUsesTransportPrincipal(String tool) {
    // when
    CallToolResult result = scenario.stateless(tool, McpPrincipalScenario.ALICE);

    // then
    assertThat(result.isError()).isNotEqualTo(true);
    assertThat(((TextContent) result.content().getFirst()).text())
        .contains("alice")
        .doesNotContain("local");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"search_context", "read_document", "related_documents", "assemble_context"})
  @DisplayName("principal 없는 문맥은 예외나 빈 성공 대신 인증 도구 오류를 낸다")
  void absentPrincipalIsToolError(String tool) {
    // when
    CallToolResult result = scenario.withoutPrincipal(tool);

    // then
    assertThat(result.isError()).isTrue();
    assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("인증 정보가 없습니다");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"search_context", "read_document", "related_documents", "assemble_context"})
  @DisplayName("principal이 문자열인 문맥은 인증 도구 오류를 낸다")
  void invalidPrincipalIsToolError(String tool) {
    // when
    CallToolResult result = scenario.withInvalidPrincipal(tool);

    // then
    assertThat(result.isError()).isTrue();
    assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("인증 정보가 없습니다");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"search_context", "read_document", "related_documents", "assemble_context"})
  @DisplayName("sync 도구는 단일 사용자 문서만 내보낸다")
  void syncUsesSingleUser(String tool) {
    // when
    CallToolResult result = scenario.sync(tool);

    // then
    assertThat(result.isError()).isNotEqualTo(true);
    assertThat(((TextContent) result.content().getFirst()).text())
        .contains("local")
        .doesNotContain("alice");
  }
}
