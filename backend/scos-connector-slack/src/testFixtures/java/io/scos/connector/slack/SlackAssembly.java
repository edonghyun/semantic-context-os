package io.scos.connector.slack;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SyntheticProvider;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.connector.slack.internal.SlackWebApiClient;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.SourceInstanceId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;

/**
 * Slack 커넥터의 테스트 조립: 합성 소스 하나와 가짜 서버를 가리키는 실제 어댑터. 커넥터마다 이런 클래스 하나를 두고(`JiraAssembly` 등), 런타임은 공용
 * `MemoryCollectionRuntime`·`PostgresCollectionRuntime`에 이 어댑터를 넘긴다(TESTING §3.6).
 */
public final class SlackAssembly {
  public static final ObjectMapper JSON = new ObjectMapper();
  public static final String KIND = "slack";
  public static final SourceInstanceId SOURCE = new SourceInstanceId("slack-synthetic");

  /** 합성 토큰을 담는 환경변수 이름 — 자식 JVM·Spring 조립이 이 이름으로 토큰을 넣는다. */
  public static final String TOKEN_ENV = "SLACK_SYNTHETIC_TOKEN";

  public static final String CREDENTIAL_REF = "env:" + TOKEN_ENV;

  /** 공개 채널 전부를 겹침 없이 걷는 설정 — 증분 수집이 워터마크 뒤만 본다. */
  public static final String PUBLIC_CHANNELS_NO_OVERLAP =
      "{\"conversationTypes\":[\"public_channel\"],\"historyOverlapSeconds\":0}";

  private SlackAssembly() {}

  /** 켜진 합성 Slack 소스. */
  public static SourceInstance instance(String configJson, Instant at) {
    return SourceInstances.of(SOURCE, KIND, configJson, CREDENTIAL_REF, at);
  }

  /** 가짜 Slack(baseUrl)에 붙는 제품 어댑터. 자격 증명 참조는 token으로 풀린다. */
  public static SlackAdapter adapter(String baseUrl, String token, Clock clock) {
    return new SlackAdapter(
        ref -> token,
        new SlackWebApiClient(
            baseUrl,
            new ProviderHttp(
                HttpClient.newHttpClient(), ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT),
            JSON),
        JSON,
        clock);
  }

  /** p4 데이터셋의 가짜 Slack과 그 위의 어댑터(TS 생성기와 같은 설정: 공개 채널, 겹침 0). */
  public static SyntheticProvider synthetic(Clock clock) {
    FakeSlackServer server;
    try {
      server =
          new FakeSlackServer(
              Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"))
                  .resolve("slack-dataset.json"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new SyntheticProvider(
        instance(PUBLIC_CHANNELS_NO_OVERLAP, clock.instant()),
        adapter(server.baseUrl(), server.token(), clock),
        Instant.parse(server.now()),
        SyntheticProvider.noEdit(KIND),
        server);
  }
}
