package io.scos.connector.tiro.internal;

import io.scos.engine.support.call.ProviderHttp;
import io.scos.engine.support.call.RetryAfter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tiro REST API 읽기: GET만, `Authorization: Bearer <key>`·`Accept: application/json`. 교환은 {@link
 * ProviderHttp}(RestClient 재사용, 시도별 timeout, ADR-0022 §4) — 리다이렉트를 따르지 않는다(3xx는 실패 상태로 남는다). 쿼리는 넘긴
 * 순서대로 form 인코딩. 2xx가 아니면 {@link TiroApiException}(상태·Retry-After), 2xx 본문이 JSON이 아니면 502로 같은 예외.
 */
public final class TiroApiClient {
  private final String baseUrl;
  private final ProviderHttp http;
  private final ObjectMapper json;
  private final Clock clock;

  public TiroApiClient(String baseUrl, ProviderHttp http, ObjectMapper json, Clock clock) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = http;
    this.json = json;
    this.clock = clock;
  }

  public JsonNode get(String token, String path, Map<String, String> query, Duration timeout) {
    String search =
        query.isEmpty()
            ? ""
            : "?"
                + query.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&"));
    ProviderHttp.Response response =
        http.get(
            URI.create(baseUrl + path + search),
            Map.of(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + token,
                HttpHeaders.ACCEPT,
                MediaType.APPLICATION_JSON_VALUE),
            timeout);
    if (!HttpStatusCode.valueOf(response.status()).is2xxSuccessful())
      throw new TiroApiException(
          response.status(), retryAfter(response.headers().getFirst(HttpHeaders.RETRY_AFTER)));
    try {
      if (response.body().isBlank()) throw new IllegalArgumentException("empty body");
      return json.readTree(response.body());
    } catch (RuntimeException e) {
      throw new TiroApiException(TiroApiException.NON_JSON_BODY, Optional.empty());
    }
  }

  /**
   * Retry-After(`Number(raw)`가 유한하면 max(0, 초), 아니면 `Date.parse(raw)`와 지금의 차이)는 공용 {@link
   * RetryAfter#secondsOrHttpDate}(Jira와 같은 식)다. 숫자는 JS Number를 따른다(10진·16진·2진·8진, JS 공백만 벗긴다).
   * Infinity가 되는 값은 없음(기본 지연)이다. V8 Date.parse만 읽는 비표준 날짜("1.2.3")는 공용 파서도 받지 않는다(CONTRACTS
   * F14-D4).
   */
  Optional<Duration> retryAfter(@Nullable String header) {
    return RetryAfter.secondsOrHttpDate(clock).read(header);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
