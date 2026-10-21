package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.JiraCollectionConfiguration;
import io.scos.connector.jira.JiraAdapter;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.SourceConfig;
import io.scos.support.TestMetrics;
import java.net.http.HttpClient;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Jira 조립(슬라이스, DB 없음): kind jira 어댑터 빈 하나가 생긴다 — `SourceAdapters` 레지스트리는 그 빈을 모은다(ADR-0022 §5).
 * API 주소는 소스 설정(사이트·cloudId)에서 정하므로 조립 속성이 없다.
 */
@DisplayName("Jira 조립은 운영 어댑터 빈 하나를 만든다(ADR-0022 §5)")
class JiraCollectionConfigurationTest {
  static final String SITE = "https://synthetic.atlassian.net";
  static final String CLOUD_ID = "0a1b2c3d-0000-4000-8000-00000000c10d";
  static final String FIELDS = "/rest/api/3/field";

  /** cloudId(범위 지정 토큰)를 둔 사이트 설정. */
  static JiraConfiguration gatewaySite() {
    return JiraConfiguration.read(
        JiraFixtures.JSON,
        new SourceConfig(
            1,
            "{\"siteUrl\":\""
                + SITE
                + "\",\"email\":\"a@example.test\",\"cloudId\":\""
                + CLOUD_ID
                + "\"}"));
  }

  private final ApplicationContextRunner jira =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(JiraCollectionConfiguration.class)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(CredentialResolver.class, () -> ref -> "unused")
          .withBean(
              ProviderCallExecutor.class,
              () -> ProviderCallExecutor.defaults(Clock.systemUTC(), TestMetrics.providerCalls()));

  @Test
  @DisplayName("kind jira 어댑터 빈 하나를 조립한다")
  void assemblesJiraAdapter() {
    // when·then
    jira.run(ctx -> assertThat(ctx.getBean(JiraAdapter.class).kind()).isEqualTo("jira"));
  }

  @Test
  @DisplayName("운영 HTTP 클라이언트 빈은 리다이렉트를 따르지 않는다")
  void productHttpClientRefusesRedirects() {
    // when·then
    jira.run(
        ctx ->
            assertThat(
                    ctx.getBean(JiraCollectionConfiguration.HTTP_CLIENT, HttpClient.class)
                        .followRedirects())
                .isEqualTo(HttpClient.Redirect.NEVER));
  }

  @Test
  @DisplayName("운영 API 클라이언트 빈은 cloudId 설정이면 Atlassian API gateway로 요청한다")
  void productApiClientCallsGateway() {
    // when·then
    jira.run(
        ctx ->
            assertThat(ctx.getBean(JiraApiClient.class).uri(gatewaySite(), FIELDS))
                .hasToString("https://api.atlassian.com/ex/jira/" + CLOUD_ID + FIELDS));
  }
}
