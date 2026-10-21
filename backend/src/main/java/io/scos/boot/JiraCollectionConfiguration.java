package io.scos.boot;

import io.scos.connector.jira.JiraAdapter;
import io.scos.connector.jira.internal.JiraApiClient;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.CredentialResolver;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Jira 커넥터 조립(F5): 어댑터 빈 하나를 등록하면 kind 레지스트리 `SourceAdapters`가 수집·등록에 jira를 더한다(ADR-0022 §5). API
 * 주소는 소스 설정에서 정한다(사이트 origin, cloudId가 있으면 Atlassian API gateway — TS와 같음). 공급자 교환은 수집 런타임과 같은 실행기의
 * 요청 timeout을 쓰는 {@link ProviderHttp}다.
 */
@Configuration(proxyBeanMethods = false)
public class JiraCollectionConfiguration {
  /** Jira 전용 HTTP 클라이언트 빈 이름(리다이렉트를 따르지 않는다 — Basic 인증이 다른 호스트로 가지 않게). */
  public static final String HTTP_CLIENT = "jiraHttpClient";

  @Bean(HTTP_CLIENT)
  HttpClient jiraHttpClient() {
    return JiraApiClient.httpClient();
  }

  @Bean
  JiraApiClient jiraApiClient(
      @Qualifier(HTTP_CLIENT) HttpClient http,
      ProviderCallExecutor calls,
      ObjectMapper json,
      Clock clock) {
    return JiraApiClient.forSites(ProviderHttp.of(http, calls), json, clock);
  }

  @Bean
  JiraAdapter jiraAdapter(
      CredentialResolver credentials, JiraApiClient client, ObjectMapper json, Clock clock) {
    return new JiraAdapter(credentials, client, json, clock);
  }
}
