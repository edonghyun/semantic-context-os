package io.scos.connector.googledrive.internal;

import io.scos.engine.support.call.ProviderHttp;
import java.net.http.HttpClient;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Drive·Workspace API 클라이언트(TS GoogleDriveApiClient): 자격 증명 JSON마다 {@link
 * GoogleDriveSession}(access token 캐시)을 만든다. 교환은 {@link ProviderHttp}(ADR-0022 §4), origin은 {@link
 * GoogleOrigins}로 고정이다.
 */
public final class GoogleDriveApiClient {
  private final ProviderHttp http;
  private final ObjectMapper json;
  private final Clock clock;
  private final GoogleOrigins origins;

  /** clock은 access token 만료 판정에만 쓴다(TS session의 now). */
  public GoogleDriveApiClient(
      ProviderHttp http, ObjectMapper json, Clock clock, GoogleOrigins origins) {
    this.http = http;
    this.json = json;
    this.clock = clock;
    this.origins = origins;
  }

  /**
   * 제품 HTTP 클라이언트: 리다이렉트를 따르지 않는다(TS fetch redirect:"error"). 따르면 Bearer access token과 토큰 끝점
   * 폼(refresh token)이 Location의 다른 곳으로 간다 — 3xx는 세션이 전송 실패로 본다.
   */
  public static HttpClient httpClient() {
    return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** 이 클라이언트가 부르는 origin. */
  public GoogleOrigins origins() {
    return origins;
  }

  /** 직렬화된 OAuth 자격 증명으로 세션을 연다. JSON·필수 키 위반은 {@link GoogleProtocolException}(요청 없음). */
  public GoogleDriveSession session(String serializedCredential) {
    return new GoogleDriveSession(
        GoogleCredential.parse(json, serializedCredential), http, json, clock, origins);
  }
}
