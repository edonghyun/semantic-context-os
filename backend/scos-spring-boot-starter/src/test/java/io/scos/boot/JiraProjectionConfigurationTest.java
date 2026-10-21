package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.jira.JiraProjector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Jira 투영 조립(슬라이스): kind jira 투영기 빈 하나가 생긴다 — `Projectors` 레지스트리가 그 빈을 모으고, 데몬 기동 검증은 등록된 수집 kind마다
 * 투영기를 요구한다(jira 어댑터만 있으면 데몬이 뜨지 않는다).
 */
@DisplayName("Jira 투영 조립은 kind jira 투영기 빈 하나를 낸다")
class JiraProjectionConfigurationTest {
  private final ApplicationContextRunner jira =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(JiraProjectionConfiguration.class);

  @Test
  @DisplayName("kind jira 투영기 빈 하나를 조립한다")
  void assemblesJiraProjector() {
    // when·then
    jira.run(ctx -> assertThat(ctx.getBean(JiraProjector.class).kind()).isEqualTo("jira"));
  }
}
