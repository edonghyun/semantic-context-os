package io.scos.engine.support.call;

import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.HeaderValues;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 공급자 HTTP 교환 하나(ADR-0022 §4): {@link ProviderCallExecutor}의 시도 안에서 부른다. 상태 코드와 무관하게 응답을 돌려준다 — 오류
 * 분류는 공급자 어댑터가 본문·헤더를 보고 한다(Slack은 200 본문의 `ok:false`, Gemini는 상태 코드). 전송 실패는 RestClient 예외 그대로다:
 * 연결·헤더 단계의 실패와 timeout은 {@code ResourceAccessException}이다. 본문을 읽는 중의 timeout은 문자열 응답({@link #get}
 * ·{@link #postForm}·{@link #postJson})에서 본문 추출 실패인 {@code RestClientException}이고, 바이트 응답({@link
 * #getBytes})에서는 {@code ResourceAccessException}이다. 둘 다 {@code RestClientException}이므로 전송 실패를 하나로
 * 다루려면 그 타입으로 본다.
 *
 * <p>시도 timeout은 보통 정책의 요청 timeout이고, 남은 예산이 그보다 짧을 때만 다르다. 요청 timeout의 시도는 한 번 만든 RestClient를 다시
 * 쓰고, 다른 timeout의 시도만 그 timeout으로 RestClient를 만든다. JDK HttpClient(연결 풀)는 둘이 함께 쓴다.
 */
public final class ProviderHttp {
  /** 응답 하나. 본문이 없으면 "". */
  public record Response(int status, HttpHeaders headers, String body) {}

  /** 바이트 응답 하나(첨부 원본). */
  public static final class BinaryResponse {
    private final int status;
    private final HttpHeaders headers;
    private final byte[] body;

    BinaryResponse(int status, HttpHeaders headers, byte[] body) {
      this.status = status;
      this.headers = headers;
      this.body = body;
    }

    public int status() {
      return status;
    }

    public HttpHeaders headers() {
      return headers;
    }

    public byte[] body() {
      return body.clone();
    }
  }

  /** 본문이 상한을 넘었다 — 상한 + 1바이트까지만 읽고 멈춘다. */
  public static final class ResponseTooLargeException extends RuntimeException {
    public ResponseTooLargeException(long maxBytes) {
      super("response body exceeds " + maxBytes + " bytes");
    }
  }

  private final HttpClient http;
  private final Duration requestTimeout;
  private final RestClient atRequestTimeout;

  public ProviderHttp(HttpClient http, Duration requestTimeout) {
    this.http = http;
    this.requestTimeout = requestTimeout;
    this.atRequestTimeout = restClient(http, requestTimeout);
  }

  public Response get(URI uri, Map<String, String> headers, Duration timeout) {
    return response(
        client(timeout)
            .get()
            .uri(uri)
            .headers(h -> safe(headers).forEach(h::set))
            .retrieve()
            .toEntity(String.class));
  }

  /**
   * 실행기 정책의 요청 timeout을 기본으로 쓰는 교환(F4 리뷰 A-M7). RestClient 재사용은 시도 timeout이 이 값과 같을 때만 일어나므로, 두 값의
   * 출처를 실행기 하나로 둔다.
   */
  public static ProviderHttp of(HttpClient http, CallExecutor calls) {
    return new ProviderHttp(http, calls.requestTimeout());
  }

  /** 한 번 만든 RestClient를 다시 쓰는 시도 timeout. */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * application/x-www-form-urlencoded POST(OAuth 토큰 갱신 등). form은 넣은 순서대로, TS URLSearchParams와 같은
   * 인코딩(공백은 +)으로 보낸다. 상태 코드와 무관하게 응답을 돌려준다.
   */
  public Response postForm(
      URI uri, Map<String, String> headers, Map<String, String> form, Duration timeout) {
    String body =
        form.entrySet().stream()
            .map(e -> formEncode(e.getKey()) + "=" + formEncode(e.getValue()))
            .collect(Collectors.joining("&"));
    return response(
        client(timeout)
            .post()
            .uri(uri)
            .headers(h -> safe(headers).forEach(h::set))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(body)
            .retrieve()
            .toEntity(String.class));
  }

  private static String formEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** 상태 코드와 무관하게 바이트로 받는다. maxBytes를 넘는 본문은 {@link ResponseTooLargeException}. */
  public BinaryResponse getBytes(
      URI uri, Map<String, String> headers, Duration timeout, long maxBytes) {
    return client(timeout)
        .get()
        .uri(uri)
        .headers(h -> safe(headers).forEach(h::set))
        .exchange(
            (request, response) -> {
              byte[] body;
              try (InputStream in = response.getBody()) {
                body = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
              }
              if (body.length > maxBytes) throw new ResponseTooLargeException(maxBytes);
              return new BinaryResponse(
                  response.getStatusCode().value(), response.getHeaders(), body);
            });
  }

  public Response postJson(URI uri, Map<String, String> headers, String json, Duration timeout) {
    return response(
        client(timeout)
            .post()
            .uri(uri)
            .headers(h -> safe(headers).forEach(h::set))
            .contentType(MediaType.APPLICATION_JSON)
            .body(json)
            .retrieve()
            .toEntity(String.class));
  }

  /** 헤더로 보낼 수 있는 값인가 — {@link HeaderValues#isSendable}. */
  public static boolean isSendableHeaderValue(String value) {
    return HeaderValues.isSendable(value);
  }

  /**
   * 보낼 수 없는 헤더 값은 보내지 않는다 — JDK는 값 전체를 담은 예외를 내므로(비밀값이 로그로 샌다) 헤더 이름만 담아 먼저 거부한다. 자격 증명은 커넥터가
   * {@code CredentialResolver.headerValue}로 먼저 거른다. 이것은 마지막 방어다.
   */
  private static Map<String, String> safe(Map<String, String> headers) {
    headers.forEach(
        (name, value) -> {
          if (!isSendableHeaderValue(value)) throw new UnsendableHeaderException(name);
        });
    return headers;
  }

  private RestClient client(Duration timeout) {
    return timeout.equals(requestTimeout) ? atRequestTimeout : restClient(http, timeout);
  }

  private static RestClient restClient(HttpClient http, Duration timeout) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(timeout);
    return RestClient.builder()
        .requestFactory(factory)
        .defaultStatusHandler(status -> true, (req, res) -> {}) // 상태 코드는 어댑터가 분류한다
        .build();
  }

  private static Response response(ResponseEntity<String> entity) {
    return new Response(
        entity.getStatusCode().value(),
        entity.getHeaders(),
        entity.getBody() == null ? "" : entity.getBody());
  }
}
