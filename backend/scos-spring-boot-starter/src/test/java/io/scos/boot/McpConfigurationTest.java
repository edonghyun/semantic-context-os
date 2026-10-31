package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.AccessMode;
import io.scos.engine.ContextEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MCP 조립은 설정으로 엔진 접근 모드를 고른다")
class McpConfigurationTest {
  @Test
  @DisplayName("다중 사용자 설정이면 제한 엔진을 조립한다")
  void multiUserBuildsRestrictedEngine() {
    // when·then
    new McpConfigurationScenario()
        .context()
        .withPropertyValues("scos.access.mode=multi-user")
        .run(
            context ->
                assertThat(context.getBean(ContextEngine.class).accessMode())
                    .isEqualTo(AccessMode.RESTRICTED));
  }

  @Test
  @DisplayName("다중 사용자 설정에 무제한 접근 빈이 있으면 기동을 거부한다")
  void multiUserRejectsUnrestrictedAccessBean() {
    // when·then
    new McpConfigurationScenario()
        .contextWithUnrestrictedAccess()
        .withPropertyValues("scos.access.mode=multi-user")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                        "scos.access.mode=multi-user but AccessParts is Unrestricted"));
  }

  @Test
  @DisplayName("단일 사용자 설정에 제한 접근 빈이 있으면 기동을 거부한다")
  void singleUserRejectsRestrictedAccessBean() {
    // when·then
    new McpConfigurationScenario()
        .contextWithRestrictedAccess()
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                        "scos.access.mode=single-user but AccessParts is Restricted"));
  }

  @Test
  @DisplayName("설정이 없으면 단일 사용자 엔진을 조립한다")
  void defaultBuildsUnrestrictedEngine() {
    // when·then
    new McpConfigurationScenario()
        .context()
        .run(
            context ->
                assertThat(context.getBean(ContextEngine.class).accessMode())
                    .isEqualTo(AccessMode.UNRESTRICTED));
  }
}
