package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.tiro.TiroAdapter;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.spi.connector.CredentialResolver;
import io.scos.support.TestMetrics;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Tiro 조립(슬라이스, DB 없음): 잘못된 `scos.collection.tiro.base-url`은 기동 실패다(ADR-0009, 이어가기 계획 2.3-15). 조립이
 * 되면 kind tiro 어댑터 빈 하나가 생긴다 — `SourceAdapters` 레지스트리는 그 빈을 모은다(ADR-0022 §5).
 */
@DisplayName("Tiro 조립은 base-url을 검증하고 어댑터 빈을 만든다(ADR-0009)")
class TiroCollectionConfigurationTest {
  private final ApplicationContextRunner tiro =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class, ValidationAutoConfiguration.class))
          .withUserConfiguration(TiroCollectionConfiguration.class)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(CredentialResolver.class, () -> ref -> "unused")
          .withBean(
              ProviderCallExecutor.class,
              () -> ProviderCallExecutor.defaults(Clock.systemUTC(), TestMetrics.providerCalls()));

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"api.tiro.ooo", "ftp://api.tiro.ooo", ""})
  @DisplayName("base-url이 http(s) URL이 아니면 기동에 실패한다")
  void rejectsBadBaseUrl(String url) {
    // when·then
    tiro.withPropertyValues("scos.collection.tiro.base-url=" + url)
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(BindValidationException.class)
                    .hasMessageContaining("baseUrl"));
  }

  @Test
  @DisplayName("기본값이면 kind tiro 어댑터 빈 하나를 조립한다")
  void assemblesTiroAdapter() {
    // when·then
    tiro.run(ctx -> assertThat(ctx.getBean(TiroAdapter.class).kind()).isEqualTo("tiro"));
  }
}
