package io.scos.connector.notion;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SyntheticProvider;
import io.scos.connector.notion.internal.FakeNotionServer;
import io.scos.connector.notion.internal.NotionApiClient;
import io.scos.connector.notion.internal.NotionFixtures;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Notion 커넥터의 테스트 조립(TESTING §3.6 1층): 합성 소스 하나와 가짜 Notion을 가리키는 제품 클라이언트·어댑터. 경로·헤더·오류 분류는 제품
 * 그대로이고 API origin만 가짜 서버 주소다.
 */
public final class NotionAssembly {
  public static final String KIND = "notion";
  public static final SourceInstanceId SOURCE = new SourceInstanceId("notion-synthetic");

  /** TS 운영 등록값과 같은 `env:` 스킴. */
  public static final String CREDENTIAL_REF = "env:NOTION_TOKEN";

  private NotionAssembly() {}

  /** 데이터셋을 서빙하는 가짜 Notion(닫는 쪽이 미등록 요청을 검사한다). */
  public static FakeNotionServer server() {
    try {
      return new FakeNotionServer(NotionFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 데이터셋의 소스 설정 하나(configs.&lt;name&gt; — roots·discover·search·missingRoot)의 JSON 원문. */
  public static String config(String name) {
    return NotionFixtures.config(name);
  }

  public static SourceInstance instance(String configJson, Instant at) {
    return SourceInstances.of(SOURCE, KIND, configJson, CREDENTIAL_REF, at);
  }

  /** 가짜 Notion(baseUrl)을 부르는 제품 어댑터. 자격 증명 참조는 token으로 풀린다. */
  public static NotionAdapter adapter(String baseUrl, String token, Clock clock) {
    return adapter(baseUrl, token, clock, ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT);
  }

  /** 시도 timeout이 requestTimeout인 교환으로 — 실행기 정책의 요청 timeout과 같게 둔다(ADR-0022 §4). */
  public static NotionAdapter adapter(
      String baseUrl, String token, Clock clock, Duration requestTimeout) {
    return new NotionAdapter(
        ref -> token, client(baseUrl, requestTimeout), NotionFixtures.JSON, clock);
  }

  /**
   * 기다리지 않는 공급자 실행기: 기본 정책(시도 5회·전체 10분·지터 0)에 시도 timeout만 requestTimeout으로 두고, 재시도 대기는 waits에 기록만
   * 한다. 어댑터의 교환도 같은 timeout으로 만든다(ADR-0022 §4).
   */
  public static ProviderCallExecutor recordingExecutor(
      Duration requestTimeout, Clock clock, List<Duration> waits) {
    ProviderCallPolicy defaults = ProviderCallPolicy.defaults(() -> 0.0);
    return new ProviderCallExecutor(
        new ProviderCallPolicy(
            requestTimeout, defaults.maxAttempts(), defaults.maxElapsed(), () -> 0.0),
        clock,
        (delay, cancel) -> {
          waits.add(delay);
          return cancel.isCancelled();
        },
        TestMetrics.providerCalls());
  }

  /**
   * 항상 연결이 거부되는 로컬 주소(전송 실패 각본 — TS fetch failed). 포트 1(tcpmux)은 테스트가 듣지 않는다 — 임시 포트를 잡았다 닫는 방식은 그
   * 사이 다른 테스트가 같은 포트를 받으면 연결이 성공해 드물게 실패한다(재확인 f1).
   */
  public static String closedPort() {
    return REFUSED_BASE_URL;
  }

  static final String REFUSED_BASE_URL = "http://127.0.0.1:1";

  /** 가짜 Notion(baseUrl)을 부르는 제품 클라이언트. */
  public static NotionApiClient client(String baseUrl, Duration requestTimeout) {
    return new NotionApiClient(
        baseUrl,
        new ProviderHttp(NotionApiClient.httpClient(), requestTimeout),
        NotionFixtures.JSON);
  }

  /** Notion 데이터셋의 가짜 Notion과 그 위의 어댑터(TS 생성기와 같은 roots 설정). */
  public static SyntheticProvider synthetic(Clock clock) {
    FakeNotionServer server = server();
    return new SyntheticProvider(
        instance(config("roots"), clock.instant()),
        adapter(server.baseUrl(), server.token(), clock),
        Instant.parse(NotionFixtures.dataset().get("now").asString()),
        SyntheticProvider.noEdit(KIND),
        server);
  }
}
