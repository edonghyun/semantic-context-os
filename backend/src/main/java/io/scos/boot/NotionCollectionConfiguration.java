package io.scos.boot;

import io.scos.connector.notion.NotionAdapter;
import io.scos.connector.notion.internal.NotionApiClient;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.CredentialResolver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/**
 * Notion 커넥터 조립(F6 T6.9): 어댑터 빈 하나를 등록하면 kind 레지스트리 `SourceAdapters`가 수집·등록에 notion을 더한다(ADR-0022
 * §5). 공급자 교환은 수집 런타임과 같은 실행기의 요청 timeout을 쓰는 {@link ProviderHttp}다(리다이렉트를 따르지 않는다 — CONTRACTS
 * F17-D3).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotionCollectionConfiguration.NotionProperties.class)
public class NotionCollectionConfiguration {
  /** API origin(가짜 서버·프록시용). 잘못된 값은 기동 시 Bean Validation이 거부한다(ADR-0009). */
  @Validated
  @ConfigurationProperties("scos.collection.notion")
  public record NotionProperties(
      @DefaultValue("https://api.notion.com") @NotBlank @Pattern(regexp = "https?://.+")
          String baseUrl) {}

  @Bean
  NotionAdapter notionAdapter(
      NotionProperties props,
      CredentialResolver credentials,
      ProviderCallExecutor calls,
      ObjectMapper json,
      Clock clock) {
    return new NotionAdapter(
        credentials,
        new NotionApiClient(
            props.baseUrl(), ProviderHttp.of(NotionApiClient.httpClient(), calls), json),
        json,
        clock);
  }
}
