package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.GoogleDriveCollectionConfiguration;
import io.scos.connector.googledrive.GoogleDriveAdapter;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.spi.connector.CredentialResolver;
import io.scos.support.TestMetrics;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Drive 조립(슬라이스, DB 없음): 기본은 Google origin이고, origin 속성 다섯이 각각 그 서비스의 요청 주소가 된다. 잘못된 origin은 기동
 * 실패다(ADR-0009). kind google-drive 어댑터 빈 하나가 생긴다 — `SourceAdapters`가 그 빈을 모은다(ADR-0022 §5).
 */
@DisplayName("Drive 조립은 origin 다섯을 검증하고 배선한다(ADR-0009·0022)")
class GoogleDriveCollectionConfigurationTest {
  private final ApplicationContextRunner drive =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class, ValidationAutoConfiguration.class))
          .withUserConfiguration(GoogleDriveCollectionConfiguration.class)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(CredentialResolver.class, () -> ref -> "unused")
          .withBean(
              ProviderCallExecutor.class,
              () -> ProviderCallExecutor.defaults(Clock.systemUTC(), TestMetrics.providerCalls()));

  @Test
  @DisplayName("기본값이면 kind google-drive 어댑터 빈 하나를 조립한다")
  void assemblesAdapter() {
    // when·then
    drive.run(
        ctx -> assertThat(ctx.getBean(GoogleDriveAdapter.class).kind()).isEqualTo("google-drive"));
  }

  @Test
  @DisplayName("기본 origin은 Google이다")
  void defaultsToGoogle() {
    // when·then
    drive.run(
        ctx ->
            assertThat(ctx.getBean(GoogleDriveApiClient.class).origins())
                .isEqualTo(GoogleOrigins.GOOGLE));
  }

  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "drive-base-url,www.googleapis.com,driveBaseUrl",
    "docs-base-url,ftp://docs.googleapis.com,docsBaseUrl",
    "sheets-base-url,https://sheets.googleapis.com/v4,sheetsBaseUrl",
    "slides-base-url,'',slidesBaseUrl",
    "oauth-base-url,https://oauth2.googleapis.com/,oauthBaseUrl"
  })
  @DisplayName("경로 없는 http(s) origin이 아니면 그 속성을 밝혀 기동에 실패한다")
  void rejectsBadOrigin(String property, String value, String field) {
    // when·then
    drive
        .withPropertyValues("scos.collection.google-drive." + property + "=" + value)
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(BindValidationException.class)
                    .hasMessageContaining("field '" + field + "'"));
  }

  @Test
  @DisplayName("다섯 origin 속성이 각각 그 서비스 요청의 주소가 된다")
  void wiresEachOrigin() {
    // when
    try (GoogleOriginServers origins = new GoogleOriginServers()) {
      drive
          .withPropertyValues(origins.propertyValues())
          .run(ctx -> origins.callEachService(ctx.getBean(GoogleDriveApiClient.class)));

      // then
      assertThat(origins.receivedPaths()).isEqualTo(GoogleOriginServers.ownPaths());
    }
  }
}
