package io.scos.connector.jira;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SyntheticProvider;
import io.scos.connector.jira.internal.FakeJiraServer;
import io.scos.connector.jira.internal.JiraApiClient;
import io.scos.connector.jira.internal.JiraFixtures;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.SourceInstanceId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

/**
 * Jira 커넥터의 테스트 조립(TESTING §3.6 1층): 합성 소스 하나와 가짜 Jira를 가리키는 제품 클라이언트. 소스 설정의 siteUrl은 데이터셋의
 * Atlassian 사이트이고, 클라이언트는 그 대신 가짜 서버 주소로 부른다(API 주소 해석만 바꾼다 — 경로·인증·오류 분류는 제품 그대로).
 */
public final class JiraAssembly {
  public static final String KIND = "jira";
  public static final SourceInstanceId SOURCE = new SourceInstanceId("jira-synthetic");

  /** TS 운영 등록과 같은 `file:` 스킴 — 파일에는 API 토큰만 있고 email은 설정에 있다. */
  public static final String CREDENTIAL_REF = "file:/run/secrets/jira-synthetic-token";

  /** 데이터셋의 소스 설정(jira-dataset.json config.value). */
  public static final String SYNTHETIC_SITE =
      JiraFixtures.dataset().get("config").get("value").toString();

  private JiraAssembly() {}

  /** fixture 디렉터리(TS 생성기 산출) — 다른 패키지의 종단 테스트가 기대 파일을 읽는다. */
  public static final Path FIXTURES = JiraFixtures.DIR;

  public static SourceInstance instance(String configJson, Instant at) {
    return SourceInstances.of(SOURCE, KIND, configJson, CREDENTIAL_REF, at);
  }

  /** 가짜 Jira(baseUrl)를 부르는 제품 어댑터. 자격 증명 참조는 데이터셋 토큰으로 풀린다(Basic의 email은 소스 설정). */
  public static JiraAdapter adapter(String baseUrl, String token, Clock clock) {
    return new JiraAdapter(ref -> token, client(baseUrl, clock), JiraFixtures.JSON, clock);
  }

  /** 가짜 Jira(baseUrl)를 부르는 제품 클라이언트. */
  public static JiraApiClient client(String baseUrl, Clock clock) {
    return new JiraApiClient(
        new ProviderHttp(JiraApiClient.httpClient(), ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT),
        JiraFixtures.JSON,
        clock,
        config -> baseUrl);
  }

  /** Jira 데이터셋의 가짜 Jira와 그 위의 어댑터(TS 생성기와 같은 설정). */
  public static SyntheticProvider synthetic(Clock clock) {
    FakeJiraServer server;
    try {
      server = new FakeJiraServer(JiraFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new SyntheticProvider(
        instance(SYNTHETIC_SITE, clock.instant()),
        adapter(server.baseUrl(), server.token(), clock),
        Instant.parse(JiraFixtures.dataset().get("now").asString()),
        SyntheticProvider.noEdit(KIND),
        server);
  }
}
