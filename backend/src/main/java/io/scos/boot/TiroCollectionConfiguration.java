package io.scos.boot;

import io.scos.connector.tiro.TiroAdapter;
import io.scos.connector.tiro.internal.TiroApiClient;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.CredentialResolver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/**
 * Tiro 커넥터 조립(F8): 어댑터 빈 하나를 등록하면 kind 레지스트리 `SourceAdapters`가 수집·등록에 tiro를 더한다(ADR-0022 §5). 공급자
 * 교환은 수집 런타임과 같은 실행기의 요청 timeout을 쓰는 {@link ProviderHttp}다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TiroCollectionConfiguration.TiroProperties.class)
public class TiroCollectionConfiguration {
  /** API origin(가짜 서버·프록시용). 잘못된 값은 기동 시 Bean Validation이 거부한다(ADR-0009). */
  @Validated
  @ConfigurationProperties("scos.collection.tiro")
  public record TiroProperties(
      @DefaultValue("https://api.tiro.ooo") @NotBlank @Pattern(regexp = "https?://.+")
          String baseUrl) {}

  @Bean
  TiroAdapter tiroAdapter(
      TiroProperties props,
      CredentialResolver credentials,
      ProviderCallExecutor calls,
      ObjectMapper json,
      Clock clock) {
    return new TiroAdapter(
        credentials,
        new TiroApiClient(
            props.baseUrl(), ProviderHttp.of(HttpClient.newHttpClient(), calls), json, clock),
        json,
        clock);
  }
}
