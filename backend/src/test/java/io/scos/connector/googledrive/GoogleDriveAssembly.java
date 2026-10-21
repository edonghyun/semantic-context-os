package io.scos.connector.googledrive;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SyntheticProvider;
import io.scos.connector.googledrive.internal.FakeGoogleServer;
import io.scos.connector.googledrive.internal.GoogleDriveApiClient;
import io.scos.connector.googledrive.internal.GoogleDriveFixtures;
import io.scos.connector.googledrive.internal.GoogleOrigins;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.SourceInstanceId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Google Drive 커넥터의 테스트 조립(TESTING §3.6 1층): 가짜 Google(한 주소가 다섯 origin을 모두 맡는다)을 가리키는 제품 클라이언트.
 * 경로·인증·오류 분류는 제품 그대로이고 origin 표만 바꾼다.
 */
public final class GoogleDriveAssembly {
  public static final String KIND = GoogleDriveAdapter.KIND;
  public static final SourceInstanceId SOURCE = new SourceInstanceId("google-drive-synthetic");

  /** 데이터셋의 OAuth 자격 증명 JSON(file: 스킴이 가리키는 파일 내용). */
  public static final String CREDENTIAL_JSON = GoogleDriveFixtures.credentialJson();

  /** 데이터셋의 소스 설정(google-drive-dataset.json config.value). */
  public static final String SYNTHETIC_ACCOUNT =
      GoogleDriveFixtures.dataset().get("config").get("value").toString();

  /** TS 운영 등록과 같은 `file:` 스킴 — 파일에는 OAuth 자격 증명 JSON 한 줄이 있다. */
  public static final String CREDENTIAL_REF = "file:/run/secrets/google-drive-synthetic.json";

  private GoogleDriveAssembly() {}

  public static SourceInstance instance(String configJson, Instant at) {
    return SourceInstances.of(SOURCE, KIND, configJson, CREDENTIAL_REF, at);
  }

  /** 가짜 Google(baseUrl)을 부르는 제품 어댑터. 자격 증명 참조는 이 OAuth JSON으로 풀린다. */
  public static GoogleDriveAdapter adapter(String baseUrl, String credentialJson, Clock clock) {
    return new GoogleDriveAdapter(
        ref -> credentialJson, client(baseUrl, clock), GoogleDriveFixtures.JSON, clock);
  }

  /**
   * 가짜 서버 하나 위의 다섯 origin: 서비스마다 다른 경로 접두(`/_drive` 등)를 origin으로 둔다. 가짜는 받은 접두로 실제 origin을 되돌려 기록한다
   * — 제품이 서비스에 맞는 origin을 골랐는지 요청 비교가 본다.
   */
  public static final Map<String, String> ORIGIN_PREFIXES =
      Map.of(
          "drive",
          "/_drive",
          "docs",
          "/_docs",
          "sheets",
          "/_sheets",
          "slides",
          "/_slides",
          "oauth",
          "/_oauth");

  public static GoogleOrigins origins(String baseUrl) {
    return new GoogleOrigins(
        URI.create(baseUrl + ORIGIN_PREFIXES.get("drive")),
        URI.create(baseUrl + ORIGIN_PREFIXES.get("docs")),
        URI.create(baseUrl + ORIGIN_PREFIXES.get("sheets")),
        URI.create(baseUrl + ORIGIN_PREFIXES.get("slides")),
        URI.create(baseUrl + ORIGIN_PREFIXES.get("oauth")));
  }

  /** 경로가 어느 서비스 origin의 것인지(테스트 덮어쓰기용 — Docs·Slides는 둘째 경로 조각으로 가른다). */
  public static String serviceOf(String path) {
    if (path.startsWith("/drive/v3/")) return "drive";
    if (path.startsWith("/v1/documents/")) return "docs";
    if (path.startsWith("/v4/")) return "sheets";
    if (path.startsWith("/v1/presentations/")) return "slides";
    return "oauth";
  }

  /**
   * 데이터 API는 baseUrl, OAuth 토큰 끝점만 다른 주소(oauthBaseUrl)인 제품 어댑터 — 토큰 끝점이 준 access token의 처리를 데이터
   * 공급자와 떼어 볼 때(커넥터 공통 자격 증명 헤더 계약).
   */
  public static GoogleDriveAdapter adapterWithTokenEndpoint(
      String baseUrl, String oauthBaseUrl, String credentialJson, Clock clock) {
    GoogleOrigins data = origins(baseUrl);
    GoogleOrigins split =
        new GoogleOrigins(
            data.drive(),
            data.docs(),
            data.sheets(),
            data.slides(),
            URI.create(oauthBaseUrl + ORIGIN_PREFIXES.get("oauth")));
    return new GoogleDriveAdapter(
        ref -> credentialJson,
        new GoogleDriveApiClient(
            new ProviderHttp(
                GoogleDriveApiClient.httpClient(), ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT),
            GoogleDriveFixtures.JSON,
            clock,
            split),
        GoogleDriveFixtures.JSON,
        clock);
  }

  /** 가짜 Google(baseUrl)을 부르는 제품 클라이언트. clock은 access token 만료 판정용이다. */
  public static GoogleDriveApiClient client(String baseUrl, Clock clock) {
    return new GoogleDriveApiClient(
        new ProviderHttp(
            GoogleDriveApiClient.httpClient(), ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT),
        GoogleDriveFixtures.JSON,
        clock,
        origins(baseUrl));
  }

  /** Drive 데이터셋의 가짜 Google과 그 위의 어댑터(TS 생성기와 같은 설정, 첫 run 라우트). */
  public static SyntheticProvider synthetic(Clock clock) {
    FakeGoogleServer server;
    try {
      server = new FakeGoogleServer(GoogleDriveFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new SyntheticProvider(
        instance(SYNTHETIC_ACCOUNT, clock.instant()),
        adapter(server.baseUrl(), CREDENTIAL_JSON, clock),
        Instant.parse(GoogleDriveFixtures.dataset().get("now").asString()),
        SyntheticProvider.noEdit(KIND),
        server);
  }
}
