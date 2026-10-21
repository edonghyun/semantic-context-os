package io.scos.boot;

import io.scos.connector.googledrive.GoogleDriveAdapter;
import io.scos.connector.googledrive.internal.GoogleDriveApiClient;
import io.scos.connector.googledrive.internal.GoogleOrigins;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.CredentialResolver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Drive 커넥터 조립(F7): 어댑터 빈 하나를 등록하면 kind 레지스트리 `SourceAdapters`가 수집·등록에 google-drive를 더한다
 * (ADR-0022 §5). 다섯 origin(Drive·Docs·Sheets·Slides API, OAuth 토큰 끝점)은 속성이고 기본값은 Google이다 — 가짜
 * 서버·프록시만 바꾼다. 공급자 교환은 리다이렉트를 따르지 않는 {@link GoogleDriveApiClient#httpClient()} 위의 {@link
 * ProviderHttp}다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleDriveCollectionConfiguration.GoogleDriveProperties.class)
public class GoogleDriveCollectionConfiguration {
  private static final String HTTP_URL = "https?://[^/]+";

  /** origin들. 경로 없는 http(s) origin만 받는다 — 잘못된 값은 기동 시 Bean Validation이 거부한다(ADR-0009). */
  @Validated
  @ConfigurationProperties("scos.collection.google-drive")
  public record GoogleDriveProperties(
      @DefaultValue("https://www.googleapis.com") @NotBlank @Pattern(regexp = HTTP_URL)
          String driveBaseUrl,
      @DefaultValue("https://docs.googleapis.com") @NotBlank @Pattern(regexp = HTTP_URL)
          String docsBaseUrl,
      @DefaultValue("https://sheets.googleapis.com") @NotBlank @Pattern(regexp = HTTP_URL)
          String sheetsBaseUrl,
      @DefaultValue("https://slides.googleapis.com") @NotBlank @Pattern(regexp = HTTP_URL)
          String slidesBaseUrl,
      @DefaultValue("https://oauth2.googleapis.com") @NotBlank @Pattern(regexp = HTTP_URL)
          String oauthBaseUrl) {
    GoogleOrigins origins() {
      return new GoogleOrigins(
          URI.create(driveBaseUrl),
          URI.create(docsBaseUrl),
          URI.create(sheetsBaseUrl),
          URI.create(slidesBaseUrl),
          URI.create(oauthBaseUrl));
    }
  }

  @Bean
  GoogleDriveApiClient googleDriveApiClient(
      GoogleDriveProperties props, ProviderCallExecutor calls, ObjectMapper json, Clock clock) {
    return new GoogleDriveApiClient(
        ProviderHttp.of(GoogleDriveApiClient.httpClient(), calls), json, clock, props.origins());
  }

  @Bean
  GoogleDriveAdapter googleDriveAdapter(
      GoogleDriveApiClient client, CredentialResolver credentials, ObjectMapper json, Clock clock) {
    return new GoogleDriveAdapter(credentials, client, json, clock);
  }
}
