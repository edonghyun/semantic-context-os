package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.SourceConfig;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 운영 조립의 Jira 클라이언트({@link JiraApiClient#forSites}, 빈 `JiraCollectionConfiguration`이 쓴다): 요청 주소는 소스
 * 설정이 정한다 — cloudId(범위 지정 토큰)가 있으면 Atlassian API gateway, 없으면 사이트 origin. 인증 정보를 어디로 보내는지가 걸린 계약이다.
 */
@DisplayName("운영 Jira 클라이언트는 소스 설정이 정한 주소로 인증 정보를 보낸다")
class JiraProductClientTest {
  static final String SITE = "https://synthetic.atlassian.net";
  static final String CLOUD_ID = "0a1b2c3d-0000-4000-8000-00000000c10d";
  static final String FIELDS = "/rest/api/3/field";

  final JiraApiClient client =
      JiraApiClient.forSites(
          new ProviderHttp(JiraApiClient.httpClient(), ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT),
          JiraFixtures.JSON,
          Clock.systemUTC());

  static JiraConfiguration site(String extra) {
    return JiraConfiguration.read(
        JiraFixtures.JSON,
        new SourceConfig(
            1, "{\"siteUrl\":\"" + SITE + "\",\"email\":\"a@example.test\"" + extra + "}"));
  }

  @Test
  @DisplayName("cloudId가 있으면 Atlassian API gateway로 요청한다")
  void callsGatewayWithCloudId() {
    // when
    URI uri = client.uri(site(",\"cloudId\":\"" + CLOUD_ID + "\""), FIELDS);

    // then
    assertThat(uri).hasToString("https://api.atlassian.com/ex/jira/" + CLOUD_ID + FIELDS);
  }

  @Test
  @DisplayName("cloudId가 없으면 사이트 origin으로 요청한다")
  void callsSiteWithoutCloudId() {
    // when
    URI uri = client.uri(site(""), FIELDS);

    // then
    assertThat(uri).hasToString(SITE + FIELDS);
  }
}
