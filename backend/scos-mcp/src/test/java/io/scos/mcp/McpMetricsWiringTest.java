package io.scos.mcp;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.scos.boot.McpConfiguration;
import io.scos.boot.MetricsConfiguration;
import io.scos.engine.internal.search.SearchMetrics;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * MCP 조립의 지표 배선(ADR-0009·ADR-0020 §3): 조립된 도구가 부른 검색 단계 시간이 앱의 MeterRegistry에 남는다. 슬라이스(DB 없음, 메모리
 * 엔진).
 */
@DisplayName("MCP 조립의 검색 단계 시간은 앱 MeterRegistry에 남는다(ADR-0020 §3)")
class McpMetricsWiringTest {
  static final Map<String, Object> QUERY = Map.of("query", "정산 규정");

  private final ApplicationContextRunner mcp =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(
              McpMemoryEngine.class, MetricsConfiguration.class, McpConfiguration.class)
          .withPropertyValues("scos.mcp.space-id=legacy", "scos.rerank.provider=none");

  /** 조립된 McpTools를 한 번 부르고 앱 레지스트리를 돌려준다. */
  MeterRegistry afterMcpCall(Consumer<McpTools> call) {
    AtomicReference<MeterRegistry> registry = new AtomicReference<>();
    mcp.run(
        ctx -> {
          call.accept(ctx.getBean(McpTools.class));
          registry.set(ctx.getBean(MeterRegistry.class));
        });
    return registry.get();
  }

  @Test
  @DisplayName("조립된 assemble_context는 앱 레지스트리에 단계 타이머를 기록한다")
  void assembleContextRecordsStagesInAppRegistry() {
    // when
    MeterRegistry registry = afterMcpCall(tools -> tools.assembleContext(QUERY));

    // then
    assertThatMetrics(registry)
        .hasTimerCount(SearchMetrics.STAGE, Tags.of("recipe", "assemble", "stage", "lexical"), 1);
  }

  @Test
  @DisplayName("조립된 search_context는 앱 레지스트리에 단계 타이머를 기록한다")
  void searchContextRecordsStagesInAppRegistry() {
    // when
    MeterRegistry registry = afterMcpCall(tools -> tools.searchContext(QUERY));

    // then
    assertThatMetrics(registry)
        .hasTimerCount(SearchMetrics.STAGE, Tags.of("recipe", "search", "stage", "lexical"), 1);
  }
}
