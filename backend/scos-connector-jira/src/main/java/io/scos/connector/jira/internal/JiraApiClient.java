package io.scos.connector.jira.internal;

import io.scos.engine.support.call.ProviderHttp;
import io.scos.engine.support.call.RetryAfter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Jira REST 클라이언트(TS JiraApiClient): 시도 하나의 교환만 한다 — 재시도·대기는 {@code ProviderCallExecutor}의 몫이다. 교환은
 * {@link ProviderHttp}(ADR-0022 §4). Basic 인증(설정 email + 자격 증명 토큰), {@code Accept:
 * application/json}, 경로는 /rest/로 시작하고 역슬래시·줄바꿈이 없어야 한다. 2xx가 아니면 {@link JiraApiException}(상태,
 * Retry-After). 리다이렉트는 따르지 않는다 — 3xx는 응답이 없는 전송 실패로 본다(TS fetch redirect:"error"). 상태가 없는 실패는
 * {@link JiraErrors}가 전송 실패로 분류한다. 스레드 안전하다(동시 읽기 4).
 */
public final class JiraApiClient {
  /** 첨부 원본 하나. 미디어 유형은 응답 Content-Type(없으면 application/octet-stream). */
  public static final class Attachment {
    private final String mediaType;
    private final byte[] bytes;

    Attachment(String mediaType, byte[] bytes) {
      this.mediaType = mediaType;
      this.bytes = bytes;
    }

    public String mediaType() {
      return mediaType;
    }

    /**
     * 받은 바이트 그대로(복사하지 않는다): 패키지 안에서 캡처로만 넘긴다. 첨부는 50 MiB까지이고 배치가 동시 4라 사본 하나가 수백 MiB가 된다(F5 리뷰
     * m12).
     */
    byte[] bytes() {
      return bytes;
    }
  }

  /** 리다이렉트 응답 — 따르지 않고 상태 없는 전송 실패로 분류된다. */
  static final class RedirectRefused extends RuntimeException {
    RedirectRefused() {
      super("Jira redirect refused");
    }
  }

  static final String OCTET_STREAM = "application/octet-stream";
  private static final Pattern UNSAFE_PATH = Pattern.compile("[\\\\\\r\\n]");

  private static final int SUCCESS_MIN = 200;
  private static final int SUCCESS_MAX = 299;
  private static final int REDIRECT_MIN = 300;
  private static final int REDIRECT_MAX = 399;
  private static final int BAD_GATEWAY = 502;

  private final ProviderHttp http;
  private final ObjectMapper json;
  private final Clock clock;
  private final Function<JiraConfiguration, String> apiBase;

  /** apiBase: 설정 → API 주소(운영은 {@code JiraConfiguration::apiBase} — 사이트 origin 또는 cloud gateway). */
  public JiraApiClient(
      ProviderHttp http,
      ObjectMapper json,
      Clock clock,
      Function<JiraConfiguration, String> apiBase) {
    this.http = http;
    this.json = json;
    this.clock = clock;
    this.apiBase = apiBase;
  }

  /**
   * 제품 HTTP 클라이언트: 리다이렉트를 따르지 않는다(TS fetch {@code redirect: "error"}) — Basic 인증 헤더를 실은 요청이
   * Location의 다른 호스트로 가지 않게. 3xx는 {@link #requireSuccess}가 전송 실패로 거부한다. 운영 빈과 테스트 조립이 이것 하나를 쓴다.
   */
  public static HttpClient httpClient() {
    return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** 운영 조립: API 주소는 소스 설정이 정한다 — 사이트 origin, cloudId가 있으면 Atlassian API gateway(TS와 같음). */
  public static JiraApiClient forSites(ProviderHttp http, ObjectMapper json, Clock clock) {
    return new JiraApiClient(http, json, clock, JiraConfiguration::apiBase);
  }

  /** JSON 응답 하나. pathAndQuery는 인코딩을 마친 경로 + 쿼리다. 2xx인데 JSON 본문이 아니면(빈 본문 포함) HTTP 502 실패. */
  JsonNode json(JiraConfiguration config, String token, String pathAndQuery, Duration timeout) {
    ProviderHttp.Response response =
        http.get(uri(config, pathAndQuery), headers(config, token), timeout);
    requireSuccess(response.status(), response.headers());
    JsonNode body;
    try {
      body = json.readTree(response.body());
    } catch (JacksonException e) {
      throw new JiraApiException(BAD_GATEWAY, Optional.empty());
    }
    // 빈 본문(공백뿐·204 포함)은 Jackson이 예외 없이 missing으로 읽는다 — TS response.json()처럼 502로 본다
    if (body.isMissingNode()) throw new JiraApiException(BAD_GATEWAY, Optional.empty());
    return body;
  }

  /**
   * 첨부 원본(redirect=false — 서명된 미디어 URL로 돌리지 않고 바이트를 받는다). maxBytes를 넘으면 {@link
   * ProviderHttp.ResponseTooLargeException}.
   */
  Attachment attachment(
      JiraConfiguration config, String token, String id, Duration timeout, long maxBytes) {
    String path = "/rest/api/3/attachment/content/" + segment(id) + "?redirect=false";
    ProviderHttp.BinaryResponse response =
        http.getBytes(uri(config, path), headers(config, token), timeout, maxBytes);
    requireSuccess(response.status(), response.headers());
    String mediaType = response.headers().getFirst(HttpHeaders.CONTENT_TYPE);
    return new Attachment(mediaType == null ? OCTET_STREAM : mediaType, response.body());
  }

  /** 경로 조각 하나(TS encodeURIComponent: 영숫자와 -_.!~*'() 밖은 UTF-8 퍼센트 인코딩). */
  static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~");
  }

  URI uri(JiraConfiguration config, String pathAndQuery) {
    if (!pathAndQuery.startsWith("/rest/") || UNSAFE_PATH.matcher(pathAndQuery).find())
      throw new IllegalArgumentException("Invalid Jira API path");
    return URI.create(apiBase.apply(config) + pathAndQuery);
  }

  private static Map<String, String> headers(JiraConfiguration config, String token) {
    String basic =
        Base64.getEncoder()
            .encodeToString((config.email() + ":" + token).getBytes(StandardCharsets.UTF_8));
    return Map.of(
        HttpHeaders.AUTHORIZATION, "Basic " + basic, HttpHeaders.ACCEPT, "application/json");
  }

  private void requireSuccess(int status, HttpHeaders headers) {
    if (status >= SUCCESS_MIN && status <= SUCCESS_MAX) return;
    if (status >= REDIRECT_MIN && status <= REDIRECT_MAX) throw new RedirectRefused();
    throw new JiraApiException(status, retryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
  }

  /** TS Jira: 초(JS Number, max 0) 또는 HTTP-date·ISO 날짜와 지금의 차이 — 공용 {@link RetryAfter}. */
  Optional<Duration> retryAfter(String raw) {
    return RetryAfter.secondsOrHttpDate(raw, clock.instant());
  }
}
