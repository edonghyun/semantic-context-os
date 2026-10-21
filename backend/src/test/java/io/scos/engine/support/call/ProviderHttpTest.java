package io.scos.engine.support.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.support.FakeHttpServer;
import io.scos.support.HoldGate;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;

/**
 * 공급자 HTTP 교환(ADR-0022 §4): 상태 코드와 무관하게 응답을 돌려주고(분류는 어댑터 몫), 시도마다 받은 timeout을 지킨다. 기본 timeout의 시도는
 * 한 번 만든 RestClient를 다시 쓴다(그 자체는 관찰 대상이 아니다 — 계약은 timeout과 응답 모양).
 */
@DisplayName("공급자 HTTP 교환은 응답을 그대로 돌려주고, 시도 timeout을 지킨다(ADR-0022 §4)")
class ProviderHttpTest {
  /** 요청 timeout 30초 — 실행기 기본 정책(TS 기본값)과 같은 손 값. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /** 붙잡힌 응답보다 짧은 시도 timeout. */
  static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);

  /**
   * 붙잡힌 응답의 hang 방지 상한. timeout을 무시하는 변이에서는 상한 뒤 연결이 끊기고 JDK HttpClient가 GET을 한 번 다시 보내므로 두 배를 기다린다
   * — 그 합이 PIT 감시(15s)보다 짧아야 변이가 감시 시간 초과가 아니라 단언으로 죽는다(MUTATION.md "F2 리뷰 반영").
   */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

  static final int SERVICE_UNAVAILABLE = 503;

  final FakeHttpServer server = newServer();
  final HoldGate stalled = new HoldGate(WAIT_LIMIT);

  /** 헤더에 실린 비밀값(예외에 남으면 안 된다). */
  static final String HEADER_SECRET = "xoxb-provider-secret";

  final ProviderHttp http = new ProviderHttp(HttpClient.newHttpClient(), REQUEST_TIMEOUT);

  static FakeHttpServer newServer() {
    try {
      return new FakeHttpServer();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterEach
  void tearDown() {
    stalled.release();
    server.close();
  }

  URI path(String path) {
    return URI.create(server.baseUrl() + path);
  }

  /** 응답을 테스트가 끝날 때까지 붙잡는 경로. */
  URI stalledPath() {
    server.on(
        "GET",
        "/stalled",
        r -> {
          stalled.hold();
          return FakeHttpServer.ok("late");
        });
    return path("/stalled");
  }

  @Nested
  @DisplayName("헤더 값에 보낼 수 없는 문자가 있으면")
  class UnsendableHeader {
    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"\r\nX: y", "\n", "\u0000", "\u0001", "\u007f", "\u0100", "\u20ac"})
    @DisplayName("값을 싣지 않은 예외로 거부한다")
    void refusesUnsendableHeader(String unsendable) {
      // given
      server.on("GET", "/plain", r -> FakeHttpServer.ok("{}"));

      // when
      Throwable refused =
          catchThrowable(
              () ->
                  http.get(
                      path("/plain"),
                      Map.of("Authorization", "Bearer " + HEADER_SECRET + unsendable),
                      REQUEST_TIMEOUT));

      // then
      assertThat(refused)
          .isInstanceOf(UnsendableHeaderException.class)
          .hasMessageNotContaining(HEADER_SECRET);
    }

    @Test
    @DisplayName("그 문자가 값 맨 앞에 있어도 거부한다")
    void refusesLeadingUnsendable() {
      // when
      Throwable refused =
          catchThrowable(
              () ->
                  http.get(
                      path("/plain"),
                      Map.of("Authorization", "\u0000" + HEADER_SECRET),
                      REQUEST_TIMEOUT));

      // then
      assertThat(refused)
          .isInstanceOf(UnsendableHeaderException.class)
          .hasMessageNotContaining(HEADER_SECRET);
    }
  }

  @Nested
  @DisplayName("실행기를 거친 호출에서 헤더 값이 거부되면(분류기가 전송 실패로 보더라도)")
  class ThroughExecutor {
    final AtomicInteger attempts = new AtomicInteger();

    CallFailureException refusedCall() {
      server.on("GET", "/plain", r -> FakeHttpServer.ok("{}"));
      ProviderCallExecutor executor =
          new ProviderCallExecutor(
              ProviderCallPolicy.defaults(() -> 0.0),
              Clock.systemUTC(),
              (delay, cancel) -> cancel.isCancelled(),
              TestMetrics.providerCalls());
      return catchThrowableOfType(
          CallFailureException.class,
          () ->
              executor.execute(
                  "test.get",
                  timeout -> {
                    attempts.incrementAndGet();
                    return http.get(
                        path("/plain"), Map.of("Authorization", HEADER_SECRET + "\r"), timeout);
                  },
                  e -> CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, "transport"),
                  CancellationToken.none()));
    }

    @Test
    @DisplayName("invalid_configuration으로 끝난다")
    void failsAsInvalidConfiguration() {
      // when
      CallFailureException failure = refusedCall();

      // then
      assertThat(failure)
          .isNotNull()
          .extracting(f -> f.failure().kind())
          .isEqualTo(CallFailure.Kind.INVALID_CONFIGURATION);
    }

    @Test
    @DisplayName("한 번만 시도한다")
    void triesOnce() {
      // when
      refusedCall();

      // then
      assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("공급자에 요청을 보내지 않는다")
    void sendsNoRequest() {
      // when
      refusedCall();

      // then
      assertThat(server.received()).isEmpty();
    }
  }

  @Nested
  @DisplayName("문자열 본문으로 주고받으면")
  class TextExchange {
    @Test
    @DisplayName("HTAB·Latin-1 헤더 값은 그대로 보낸다")
    void sendsTabAndLatin1() {
      // given
      server.on("GET", "/plain", r -> FakeHttpServer.ok("{}"));

      // when
      var response = http.get(path("/plain"), Map.of("X-Value", "a\tb é"), REQUEST_TIMEOUT);

      // then
      assertThat(response.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("2xx가 아닌 응답도 던지지 않고 상태·헤더·본문을 돌려준다")
    void returnsNonSuccessResponse() {
      // given
      server.on(
          "GET",
          "/busy",
          r ->
              new FakeHttpServer.Response(
                  SERVICE_UNAVAILABLE, "{\"error\":\"busy\"}", Map.of("Retry-After", "7")));

      // when
      ProviderHttp.Response response = http.get(path("/busy"), Map.of(), REQUEST_TIMEOUT);

      // then
      assertThat(response)
          .extracting(
              ProviderHttp.Response::status,
              r -> r.headers().getFirst("Retry-After"),
              ProviderHttp.Response::body)
          .containsExactly(SERVICE_UNAVAILABLE, "7", "{\"error\":\"busy\"}");
    }

    @Test
    @DisplayName("요청 헤더와 JSON 본문을 보낸다")
    void sendsHeadersAndJsonBody() {
      // given
      server.on("POST", "/embed", r -> FakeHttpServer.ok("{}"));

      // when
      http.postJson(path("/embed"), Map.of("x-goog-api-key", "k"), "{\"q\":1}", REQUEST_TIMEOUT);

      // then
      assertThat(server.received())
          .singleElement()
          .extracting(r -> r.header("x-goog-api-key"), r -> r.header("content-type"), r -> r.body())
          .containsExactly("k", "application/json", "{\"q\":1}");
    }
  }

  @Nested
  @DisplayName("응답이 시도 timeout보다 늦게 오면")
  class StalledResponse {
    URI stalledUri;

    @BeforeEach
    void stall() {
      stalledUri = stalledPath();
    }

    @Test
    @DisplayName("기본보다 짧은 시도 timeout(남은 예산)을 지킨다")
    void honorsShorterAttemptTimeout() {
      // when·then
      assertThatThrownBy(() -> http.get(stalledUri, Map.of(), SHORT_TIMEOUT))
          .isInstanceOf(ResourceAccessException.class)
          .hasRootCauseInstanceOf(HttpTimeoutException.class);
    }

    @Test
    @DisplayName("기본 시도 timeout을 지킨다")
    void honorsDefaultAttemptTimeout() {
      // given
      ProviderHttp shortDefault = new ProviderHttp(HttpClient.newHttpClient(), SHORT_TIMEOUT);

      // when·then
      assertThatThrownBy(() -> shortDefault.get(stalledUri, Map.of(), SHORT_TIMEOUT))
          .isInstanceOf(ResourceAccessException.class)
          .hasRootCauseInstanceOf(HttpTimeoutException.class);
    }
  }

  @Nested
  @DisplayName("바이트로 받을 때")
  class Bytes {
    static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
    static final long ROOMY_LIMIT = 1024;

    @BeforeEach
    void givenBinaryRoute() {
      server.on(
          "GET",
          "/file",
          r -> FakeHttpServer.Response.binary(200, PNG_HEAD, Map.of("Content-Type", "image/png")));
    }

    @Test
    @DisplayName("본문 바이트를 그대로 돌려준다")
    void returnsBodyBytes() {
      // when
      ProviderHttp.BinaryResponse response =
          http.getBytes(path("/file"), Map.of(), REQUEST_TIMEOUT, ROOMY_LIMIT);

      // then
      assertThat(response.body()).containsExactly(PNG_HEAD);
    }

    @Test
    @DisplayName("응답 헤더의 Content-Type을 돌려준다")
    void returnsContentType() {
      // when
      ProviderHttp.BinaryResponse response =
          http.getBytes(path("/file"), Map.of(), REQUEST_TIMEOUT, ROOMY_LIMIT);

      // then
      assertThat(response.headers().getFirst("Content-Type")).isEqualTo("image/png");
    }

    @Test
    @DisplayName("응답이 텍스트 charset을 밝혀도 바이트를 그대로 돌려준다")
    void keepsBytesRegardlessOfCharset() {
      // given
      byte[] notUtf8 = {(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, 0x00, (byte) 0xC3};
      server.on(
          "GET",
          "/text",
          r ->
              FakeHttpServer.Response.binary(
                  200, notUtf8, Map.of("Content-Type", "text/plain; charset=utf-8")));

      // when
      ProviderHttp.BinaryResponse response =
          http.getBytes(path("/text"), Map.of(), REQUEST_TIMEOUT, ROOMY_LIMIT);

      // then
      assertThat(response.body()).containsExactly(notUtf8);
    }

    @Test
    @DisplayName("상한과 같은 크기는 받는다")
    void acceptsBodyAtLimit() {
      // when
      ProviderHttp.BinaryResponse response =
          http.getBytes(path("/file"), Map.of(), REQUEST_TIMEOUT, PNG_HEAD.length);

      // then
      assertThat(response.body()).hasSize(PNG_HEAD.length);
    }

    @Test
    @DisplayName("상한을 넘는 본문은 끝까지 읽지 않고 거부한다")
    void rejectsBodyOverLimit() {
      // when·then
      assertThatThrownBy(
              () -> http.getBytes(path("/file"), Map.of(), REQUEST_TIMEOUT, PNG_HEAD.length - 1))
          .isInstanceOf(ProviderHttp.ResponseTooLargeException.class);
    }

    @Test
    @DisplayName("2xx가 아닌 응답도 상태와 함께 돌려준다")
    void returnsNonSuccessStatus() {
      // given
      server.on("GET", "/gone", r -> FakeHttpServer.status(404, "gone"));

      // when
      ProviderHttp.BinaryResponse response =
          http.getBytes(path("/gone"), Map.of(), REQUEST_TIMEOUT, ROOMY_LIMIT);

      // then
      assertThat(response.status()).isEqualTo(404);
    }
  }

  @Nested
  @DisplayName("폼으로 보낼 때")
  class Form {
    Map<String, String> refreshForm() {
      Map<String, String> form = new LinkedHashMap<>();
      form.put("grant_type", "refresh_token");
      form.put("refresh_token", "1//a+b c~");
      return form;
    }

    @BeforeEach
    void givenTokenRoute() {
      server.on("POST", "/token", r -> FakeHttpServer.ok("{}"));
    }

    @Test
    @DisplayName("값을 form-urlencoded로 순서대로 보낸다")
    void sendsUrlEncodedBody() {
      // when
      http.postForm(path("/token"), Map.of(), refreshForm(), REQUEST_TIMEOUT);

      // then
      assertThat(server.received().getFirst().body())
          .isEqualTo("grant_type=refresh_token&refresh_token=1%2F%2Fa%2Bb+c%7E");
    }

    @Test
    @DisplayName("요청 헤더를 보낸다")
    void sendsHeaders() {
      // when
      http.postForm(path("/token"), Map.of("x-client", "scos"), refreshForm(), REQUEST_TIMEOUT);

      // then
      assertThat(server.received().getFirst().header("x-client")).isEqualTo("scos");
    }

    @Test
    @DisplayName("Content-Type을 application/x-www-form-urlencoded로 보낸다")
    void sendsFormContentType() {
      // when
      http.postForm(path("/token"), Map.of(), refreshForm(), REQUEST_TIMEOUT);

      // then
      assertThat(server.received().getFirst().header("content-type"))
          .startsWith("application/x-www-form-urlencoded");
    }

    @Test
    @DisplayName("2xx가 아닌 응답도 상태와 본문을 돌려준다")
    void returnsNonSuccessResponse() {
      // given: 같은 경로가 400으로 답한다
      server.on("POST", "/token", r -> FakeHttpServer.status(400, "{\"error\":\"invalid_grant\"}"));

      // when
      ProviderHttp.Response response =
          http.postForm(path("/token"), Map.of(), refreshForm(), REQUEST_TIMEOUT);

      // then
      assertThat(response)
          .extracting(ProviderHttp.Response::status, ProviderHttp.Response::body)
          .containsExactly(400, "{\"error\":\"invalid_grant\"}");
    }
  }

  @Test
  @DisplayName("실행기로 만들면 실행기 정책의 요청 timeout을 기본으로 쓴다")
  void takesRequestTimeoutFromExecutor() {
    // given: 기본 정책의 요청 timeout은 30초다(TS 기본값)
    ProviderCallExecutor executor =
        ProviderCallExecutor.defaults(Clock.systemUTC(), TestMetrics.providerCalls());

    // when
    ProviderHttp fromExecutor = ProviderHttp.of(HttpClient.newHttpClient(), executor);

    // then
    assertThat(fromExecutor.requestTimeout()).isEqualTo(REQUEST_TIMEOUT);
  }
}
