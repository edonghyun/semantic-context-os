package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@DisplayName("MCP 서버 프로필은 이름·안내문·도구 설명을 정한다(C9)")
class McpServerProfileTest {
  static final McpServerProfile LOCAL =
      new McpServerProfile("scos-local-test", "9.9.9", "T", Map.of("search_context", "로컬 검색"));

  final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
  McpStdioServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop();
  }

  @Test
  @DisplayName("모르는 도구 설명을 지정하면 프로필 생성을 거부한다")
  void rejectsUnknownToolName() {
    // given
    Map<String, String> descriptions = Map.of("search_context", "검색", "search_contex", "오타");

    // when·then
    assertThatThrownBy(() -> new McpServerProfile("local", "1", "안내", descriptions))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("search_contex");
  }

  @Test
  @DisplayName("프로필을 준 stdio 서버는 initialize에 그 이름과 안내문을 싣는다")
  void profileNameAndInstructions() throws Exception {
    // given
    server =
        new McpStdioServer(
            McpStdioInitializeTest.tools(),
            LOCAL,
            McpStdioInitializeTest.stdin(McpStdioInitializeTest.INITIALIZE),
            stdout,
            McpStdioServer.DRAIN_LIMIT);

    // when
    server.start();
    server.awaitStdinEof();

    // then
    JsonNode result = initializeResult();
    assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("scos-local-test");
    assertThat(result.path("instructions").asString()).isEqualTo("T");
  }

  @Test
  @DisplayName("기본 프로필은 기존 이름·안내문 그대로다")
  void organizationIsDefault() {
    // given
    McpServerProfile profile = McpServerProfile.ORGANIZATION;

    // when
    String name = profile.name();

    // then
    assertThat(name).isEqualTo(McpToolSpecs.SERVER_NAME);
    assertThat(profile.instructions()).isEqualTo(McpToolSpecs.INSTRUCTIONS);
  }

  @Test
  @DisplayName("프로필에 없는 도구 설명은 기본 설명을 쓰고, 있는 것은 덮어쓴다")
  void toolDescriptionsFallBack() {
    // given
    var tools = McpStdioInitializeTest.tools();

    // when
    List<String> descriptions =
        McpToolSpecs.sync(tools, LOCAL).stream()
            .filter(s -> !s.tool().name().equals("search_context"))
            .map(s -> s.tool().description())
            .toList();
    String overridden =
        McpToolSpecs.sync(tools, LOCAL).stream()
            .filter(s -> s.tool().name().equals("search_context"))
            .findFirst()
            .orElseThrow()
            .tool()
            .description();

    // then
    assertThat(overridden).isEqualTo("로컬 검색");
    assertThat(descriptions).contains(McpToolSpecs.READ_DESCRIPTION);
  }

  private JsonNode initializeResult() {
    return stdout
        .toString(StandardCharsets.UTF_8)
        .lines()
        .map(McpStdioInitializeTest.JSON::readTree)
        .filter(m -> m.path("id").asInt() == McpStdioInitializeTest.INITIALIZE_ID)
        .findFirst()
        .orElseThrow()
        .path("result");
  }
}
