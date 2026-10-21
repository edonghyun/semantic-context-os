package io.scos.connector.tiro;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SyntheticProvider;
import io.scos.connector.tiro.internal.FakeTiroServer;
import io.scos.connector.tiro.internal.TiroApiClient;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.SourceInstanceId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;

/**
 * Tiro 커넥터의 테스트 조립(TESTING §3.6 1층): 합성 소스 하나, harness/fixtures/tiro 데이터셋을 서빙하는 가짜 서버, 가짜 서버를 가리키는
 * 실제 어댑터. 런타임은 공용 `MemoryCollectionRuntime`·`PostgresCollectionRuntime`에 이 어댑터를 넘긴다.
 */
public final class TiroAssembly {
  public static final ObjectMapper JSON = new ObjectMapper();
  public static final String KIND = TiroAdapter.KIND;
  public static final SourceInstanceId SOURCE = new SourceInstanceId("tiro-synthetic");
  public static final String CREDENTIAL_REF = "env:TIRO_SYNTHETIC_KEY";
  public static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.tiro.dir", "../harness/fixtures/tiro"));
  public static final Path DATASET = FIXTURES.resolve("tiro-dataset.json");

  private TiroAssembly() {}

  /** 데이터셋을 서빙하는 가짜 Tiro(닫는 쪽이 {@link FakeTiroServer#assertNoUnmatched}를 부른다). */
  public static FakeTiroServer server() {
    try {
      return new FakeTiroServer(DATASET);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 켜진 합성 Tiro 소스. */
  public static SourceInstance instance(String configJson, Instant at) {
    return SourceInstances.of(SOURCE, KIND, configJson, CREDENTIAL_REF, at);
  }

  /** 가짜 Tiro(baseUrl)에 붙는 제품 어댑터. 자격 증명 참조는 token으로 풀린다. */
  public static TiroAdapter adapter(String baseUrl, String token, Clock clock) {
    return new TiroAdapter(
        ref -> token,
        new TiroApiClient(
            baseUrl,
            new ProviderHttp(
                HttpClient.newHttpClient(), ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT),
            JSON,
            clock),
        JSON,
        clock);
  }

  /** Tiro 데이터셋의 가짜 Tiro와 그 위의 어댑터. 편집은 노트 n-alpha의 화자 정보 없는 문단(첫 페이지 둘째) 전사를 바꾼다. */
  public static SyntheticProvider synthetic(Clock clock) {
    FakeTiroServer server = server();
    return new SyntheticProvider(
        instance(server.configJson(), clock.instant()),
        adapter(server.baseUrl(), server.token(), clock),
        Instant.parse(datasetNow()),
        () -> server.editTranscript(EDITED_NOTE, EDITED_PARAGRAPH, EDITED_TRANSCRIPT),
        server);
  }

  private static String datasetNow() {
    try {
      return JSON.readTree(Files.readString(DATASET)).get("now").asString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@link #synthetic} 편집이 고치는 노트와 새 전사. */
  public static final String EDITED_NOTE = "n-alpha";

  /** 화자 정보(diarizedSegments)가 없는 문단 — 전사가 투영 본문에 그대로 들어간다. */
  public static final int EDITED_PARAGRAPH = 1;

  public static final String EDITED_TRANSCRIPT = "재수집 뒤 고친 전사 — 커넥션 풀 고갈 원인 확정";
}
