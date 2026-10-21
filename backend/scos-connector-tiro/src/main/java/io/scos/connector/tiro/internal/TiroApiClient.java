package io.scos.connector.tiro.internal;

import io.scos.engine.support.call.ProviderHttp;
import io.scos.engine.support.call.RetryAfter;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tiro REST API 읽기(TS tiro-api-client.ts): GET만, `Authorization: Bearer <key>`·`Accept:
 * application/json`. 교환은 {@link ProviderHttp}(RestClient 재사용, 시도별 timeout, ADR-0022 §4) — 리다이렉트를
 * 따르지 않는다(3xx는 실패 상태로 남는다, TS `redirect: "error"`). 쿼리는 넘긴 순서대로 form 인코딩(TS URLSearchParams). 2xx가
 * 아니면 {@link TiroApiException}(상태·Retry-After), 2xx 본문이 JSON이 아니면 502로 같은 예외.
 */
public final class TiroApiClient {
  /** JS Number의 부호 없는 2진·8진 정수 리터럴(공용 RetryAfter는 10진·16진만 읽는다). */
  private static final Pattern BINARY_OR_OCTAL = Pattern.compile("^0(?:[bB]([01]+)|[oO]([0-7]+))$");

  private static final int BINARY = 2;
  private static final int OCTAL = 8;
  private static final long MILLIS_PER_SECOND = 1000;

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
   * Retry-After(TS `Number(raw)`가 유한하면 max(0, 초), 아니면 `Date.parse(raw)`와 지금의 차이)는 공용 {@link
   * RetryAfter#secondsOrHttpDate}(TS Jira와 같은 식)다. Tiro 쪽에 남긴 것은 공용 파서에 없는 JS Number의 2진·8진
   * 정수(0b·0o) 하나다 — 공용 파서에 모드를 더하지 않고 여기 둔다(Slack·Jira TS는 이 값을 시험하지 않았고, 공용 의미를 Tiro 사정으로 넓히지
   * 않는다). Infinity가 되는 값은 TS처럼 없음(기본 지연)이다. V8 Date.parse만 읽는 비표준 날짜("1.2.3")는 공용 파서도 받지
   * 않는다(CONTRACTS F14-D4).
   */
  Optional<Duration> retryAfter(String header) {
    if (header != null) {
      Matcher radix = BINARY_OR_OCTAL.matcher(header.strip());
      if (radix.matches()) {
        double seconds =
            radix.group(1) != null
                ? new BigInteger(radix.group(1), BINARY).doubleValue()
                : new BigInteger(radix.group(2), OCTAL).doubleValue();
        return Double.isFinite(seconds)
            ? Optional.of(Duration.ofMillis((long) (seconds * MILLIS_PER_SECOND)))
            : Optional.empty();
      }
    }
    return RetryAfter.secondsOrHttpDate(header, clock.instant());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
