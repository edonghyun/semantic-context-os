package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.McpConfiguration;
import io.scos.boot.MetricsConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 조립의 질의 로그 배선(TS `SCOS_QUERY_LOG`, application.yml `scos.mcp.query-log`): 경로를 주면 조립된 도구가 검색마다
 * 그 파일에 한 줄을 덧붙이고, 주지 않으면 로그는 꺼져 있다(TS 미지정 시 비활성). 슬라이스(DB 없음, 메모리 엔진).
 */
@DisplayName("MCP 조립은 query-log 경로를 줄 때만 질의 로그를 쓴다")
class McpQueryLogWiringTest {
  static final Map<String, Object> QUERY = Map.of("query", "정산 규정");
  static final ObjectMapper JSON = new ObjectMapper();

  private final ApplicationContextRunner mcp =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(
              McpMemoryEngine.class, MetricsConfiguration.class, McpConfiguration.class)
          .withPropertyValues("scos.mcp.space-id=legacy", "scos.rerank.provider=none");

  @Test
  @DisplayName("query-log 경로를 주면 검색 한 번에 그 파일에 한 줄을 쓴다")
  void writesConfiguredLog(@TempDir Path dir) throws IOException {
    // given
    Path log = dir.resolve("mcp-queries.jsonl");

    // when
    mcp.withPropertyValues("scos.mcp.query-log=" + log)
        .run(ctx -> ctx.getBean(McpTools.class).searchContext(QUERY));

    // then
    assertThat(Files.readAllLines(log))
        .singleElement()
        .satisfies(
            line -> assertThat(JSON.readTree(line).get("query").asString()).isEqualTo("정산 규정"));
  }

  @Test
  @DisplayName("query-log가 비면 로그는 꺼져 있다")
  void disabledWhenBlank() {
    // when·then
    mcp.withPropertyValues("scos.mcp.query-log=")
        .run(ctx -> assertThat(ctx.getBean(QueryLog.class)).isSameAs(QueryLog.NONE));
  }

  @Test
  @DisplayName("query-log를 주지 않아도 로그는 꺼져 있다")
  void disabledWhenAbsent() {
    // when·then
    mcp.run(ctx -> assertThat(ctx.getBean(QueryLog.class)).isSameAs(QueryLog.NONE));
  }
}
