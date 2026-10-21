package io.scos.provider.gemini;

import static io.scos.provider.gemini.GeminiFakes.BUDGET;
import static io.scos.provider.gemini.GeminiFakes.MAX_ATTEMPTS;
import static io.scos.provider.gemini.GeminiFakes.executor;
import static io.scos.provider.gemini.GeminiFakes.startHttp;
import static io.scos.support.FakeHttpServer.inOrder;
import static io.scos.support.FakeHttpServer.ok;
import static io.scos.support.FakeHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.connector.CancellationToken;
import io.scos.spi.embedding.Embedder;
import io.scos.support.BackgroundThread;
import io.scos.support.Eventually;
import io.scos.support.FakeHttpServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini batchEmbedContents 어댑터 계약(ADR-0004·DESIGN-PATTERNS §7): 경로가 등록된 로컬 fake HTTP로
 * 2xx·429·5xx·형식 오류·개수 불일치. 실 API 호출 없음.
 */
@DisplayName("Gemini 임베딩 어댑터는 배치 하나로 보내고, 응답을 검증한다(ADR-0004)")
class GeminiEmbedderTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String API_KEY = "test-key";
  static final String ENDPOINT = "/v1beta/models/gemini-embedding-001:batchEmbedContents";
  static final int DIM = 768;
  static final int WRONG_DIM = 3;
  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
  static final Duration RETRY_AFTER = Duration.ofSeconds(7);
  static final Duration LONG_BUDGET = Duration.ofMinutes(10);
  static final int RATE_LIMITED = 429;
  static final int UNAVAILABLE = 503;
  static final int UNAUTHORIZED = 401;

  final FakeHttpServer http = startHttp();
  final GeminiEmbedder embedder = new GeminiEmbedder(http.baseUrl(), API_KEY, executor(), JSON);

  @AfterEach
  void stop() {
    http.assertNoUnmatched();
    http.close();
  }

  /** 재시도 3회·대기 없음(지터 0, waiter 즉시 반환) — 결정적. */
  /** n개 벡터 응답. i번째 벡터의 값은 전부 i+1. */
  static String vectors(int n, int dim) {
    StringBuilder sb = new StringBuilder("{\"embeddings\":[");
    for (int i = 0; i < n; i++) {
      sb.append(i > 0 ? "," : "").append("{\"values\":[");
      for (int d = 0; d < dim; d++) sb.append(d > 0 ? "," : "").append(i + 1).append(".0");
      sb.append("]}");
    }
    return sb.append("]}").toString();
  }

  void givenGeminiAnswers(FakeHttpServer.Response... responses) {
    http.on("POST", ENDPOINT, inOrder(responses));
  }

  int requests() {
    return http.received().size();
  }

  /** 첫 요청의 본문. */
  JsonNode sentBody() {
    return JSON.readTree(http.received().get(0).body());
  }

  @Nested
  @DisplayName("입력 a·b에 2xx로 답하면")
  class TwoTextsSucceed {
    @BeforeEach
    void givenTwoVectors() {
      givenGeminiAnswers(ok(vectors(2, DIM)));
    }

    @Test
    @DisplayName("texts[i]가 vectors[i]가 된다")
    void embedsInOrder() {
      // when
      List<double[]> got = embedder.embed(List.of("a", "b"));

      // then
      assertThat(got).extracting(v -> v[0]).containsExactly(1.0, 2.0);
    }

    @Test
    @DisplayName("벡터는 768차원이다")
    void vectorsHaveDeclaredDimension() {
      // when
      List<double[]> got = embedder.embed(List.of("a", "b"));

      // then
      assertThat(got).allSatisfy(v -> assertThat(v).hasSize(DIM));
    }

    @Test
    @DisplayName("배치를 한 번의 요청으로 보낸다")
    void sendsOneBatchRequest() {
      // when
      embedder.embed(List.of("a", "b"));

      // then
      assertThat(requests()).isEqualTo(1);
    }

    @Test
    @DisplayName("요청 본문은 입력마다 requests 항목 하나를 담는다")
    void requestPerInput() {
      // when
      embedder.embed(List.of("a", "b"));

      // then
      assertThat(sentBody().get("requests").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("각 요청은 TS와 같은 모델과 출력 차원을 보낸다")
    void requestBodyMatchesTs() {
      // when
      embedder.embed(List.of("a", "b"));

      // then
      JsonNode second = sentBody().get("requests").get(1);
      assertThat(second.get("model").asString()).isEqualTo("models/gemini-embedding-001");
      assertThat(second.get("outputDimensionality").asInt()).isEqualTo(DIM);
    }

    @Test
    @DisplayName("각 요청의 content.parts[0].text는 그 입력이다")
    void requestCarriesInputText() {
      // when
      embedder.embed(List.of("a", "b"));

      // then
      JsonNode second = sentBody().get("requests").get(1);
      assertThat(second.get("content").get("parts").get(0).get("text").asString()).isEqualTo("b");
    }
  }

  @Nested
  @DisplayName("입력 하나에 2xx로 답하면")
  class OneTextSucceeds {
    @BeforeEach
    void givenOneVector() {
      givenGeminiAnswers(ok(vectors(1, DIM)));
    }

    @Test
    @DisplayName("키는 x-goog-api-key 헤더로 보낸다")
    void sendsKeyInHeader() {
      // when
      embedder.embed(List.of("a"));

      // then
      assertThat(http.received().get(0).header("x-goog-api-key")).isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("키를 쿼리스트링에 싣지 않는다")
    void sendsKeyInHeaderOnly() {
      // when
      embedder.embed(List.of("a"));

      // then
      assertThat(http.received().get(0).query()).doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("본문은 JSON으로 보낸다")
    void sendsJson() {
      // when
      embedder.embed(List.of("a"));

      // then
      assertThat(http.received().get(0).header("content-type")).isEqualTo("application/json");
    }
  }

  @Nested
  @DisplayName("빈 입력이면")
  class EmptyInput {
    @Test
    @DisplayName("빈 결과다")
    void emptyInputIsEmpty() {
      // when
      List<double[]> got = embedder.embed(List.of());

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("요청하지 않는다")
    void emptyInputMakesNoRequest() {
      // when
      embedder.embed(List.of());

      // then
      assertThat(requests()).isZero();
    }
  }

  @Nested
  @DisplayName("응답이 잘못되면")
  class BadResponses {
    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {RATE_LIMITED, UNAVAILABLE, UNAUTHORIZED})
    @DisplayName("비2xx 메시지는 본문(비밀·원문 가능) 없이 상태 코드만 밝힌다(H6)")
    void httpErrorsHideBody(int code) {
      // given
      givenGeminiAnswers(status(code, "{\"error\":{\"message\":\"secret detail\"}}"));

      // when·then
      assertThatThrownBy(() -> embedder.embed(List.of("a")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessage("Gemini embeddings " + code)
          .message()
          .doesNotContain("secret");
    }

    @Test
    @DisplayName("응답 개수가 요청과 다르면 실패한다(조용한 정렬 어긋남 금지)")
    void countMismatch() {
      // given
      givenGeminiAnswers(ok(vectors(1, DIM)));

      // when·then
      assertThatThrownBy(() -> embedder.embed(List.of("a", "b")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessageContaining("count");
    }

    @Test
    @DisplayName("응답 차원이 768이 아니면 실패한다")
    void dimMismatch() {
      // given
      givenGeminiAnswers(ok(vectors(1, WRONG_DIM)));

      // when·then
      assertThatThrownBy(() -> embedder.embed(List.of("a")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessageContaining("dim");
    }

    @ParameterizedTest(name = "본문 {0}")
    @ValueSource(strings = {"not json", "{\"unexpected\":1}"})
    @DisplayName("형식 오류(JSON 아님·embeddings 없음)는 실패한다")
    void malformedResponse(String body) {
      // given
      givenGeminiAnswers(ok(body));

      // when·then
      assertThatThrownBy(() -> embedder.embed(List.of("a")))
          .isInstanceOf(Embedder.EmbedderException.class);
    }
  }

  @Nested
  @DisplayName("비2xx 본문의 error에 코드 필드가 있으면(TS summarizeEmbedderFailure)")
  class ProviderErrorCode {
    @ParameterizedTest(name = "HTTP {0} {1} → {2}")
    @CsvSource(
        delimiter = '|',
        textBlock =
            """
            429 | {"error":{"code":429,"message":"secret detail","status":"RESOURCE_EXHAUSTED"}} | Gemini embeddings 429: RESOURCE_EXHAUSTED
            503 | {"error":{"code":503,"message":"secret detail","status":"UNAVAILABLE"}}        | Gemini embeddings 503: UNAVAILABLE
            400 | {"error":{"code":"BAD_CODE","type":"BAD_TYPE","status":"BAD_STATUS"}}          | Gemini embeddings 400: BAD_CODE
            400 | {"error":{"code":"","type":"BAD_TYPE","status":"BAD_STATUS"}}                  | Gemini embeddings 400: BAD_TYPE
            """)
    @DisplayName("code·type·status 중 처음 문자열이 식별자면 \": <값>\"을 붙인다")
    void appendsIdentifierCode(int code, String body, String expected) {
      // given
      givenGeminiAnswers(status(code, body));

      // when·then
      assertThatThrownBy(() -> embedder.embed(List.of("a")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessage(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "{\"error\":{\"status\":\"secret detail here\"}}",
          "{\"error\":{\"status\":\"SECRET_0123456789_0123456789_0123456789_0123456789_0123456789_XYZ\"}}",
          "{\"error\":{\"code\":\"secret detail\",\"status\":\"LATER_STATUS\"}}"
        })
    @DisplayName("처음 값이 식별자가 아니면(자유 문장·65자) 뒤 필드로 넘어가지 않고 상태 코드만 밝힌다(H6)")
    void nonIdentifierIsNotAppended(String body) {
      // given
      givenGeminiAnswers(status(400, body));

      // when·then
      assertThatThrownBy(() -> embedder.embed(List.of("a")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessage("Gemini embeddings 400");
    }
  }

  @Nested
  @DisplayName("503이 두 번 온 뒤 2xx면")
  class ServerErrorThenSuccess {
    @BeforeEach
    void givenTwoUnavailableThenOk() {
      givenGeminiAnswers(status(UNAVAILABLE, "{}"), status(UNAVAILABLE, "{}"), ok(vectors(1, DIM)));
    }

    @Test
    @DisplayName("세 번째 요청의 결과를 돌려준다")
    void retriesOnServerError() {
      // when
      List<double[]> got = embedder.embed(List.of("a"));

      // then
      assertThat(got).hasSize(1);
    }

    @Test
    @DisplayName("최대 시도 수까지 다시 요청한다")
    void retriesServerErrorUpToMaxAttempts() {
      // when
      embedder.embed(List.of("a"));

      // then
      assertThat(requests()).isEqualTo(MAX_ATTEMPTS);
    }
  }

  @Nested
  @DisplayName("429가 계속되면")
  class RateLimitedPersistently {
    @BeforeEach
    void givenAlwaysRateLimited() {
      givenGeminiAnswers(status(RATE_LIMITED, "{}"));
    }

    @Test
    @DisplayName("status 429로 실패한다")
    void rateLimitExhaustsAttempts() {
      // when·then
      assertThatThrownBy(() -> embedder.embed(List.of("a")))
          .isInstanceOfSatisfying(
              Embedder.EmbedderException.class, e -> assertThat(e.status()).hasValue(RATE_LIMITED));
    }

    @Test
    @DisplayName("실패로 끝나고, 최대 시도 수까지 다시 요청한다")
    void retriesRateLimitUpToMaxAttempts() {
      // when
      Throwable failure = catchThrowable(() -> embedder.embed(List.of("a")));

      // then
      assertThat(failure).isInstanceOf(Embedder.EmbedderException.class);
      assertThat(requests()).isEqualTo(MAX_ATTEMPTS);
    }
  }

  @Nested
  @DisplayName("429에 Retry-After 7초가 온 뒤 2xx면")
  class RateLimitedWithRetryAfter {
    /** 대기를 기록만 하는 실행기(실제 대기 없음) 위의 임베더. */
    GeminiEmbedder embedderRecordingWaits(List<Duration> waits) {
      return new GeminiEmbedder(
          http.baseUrl(),
          API_KEY,
          executor(
              (delay, cancel) -> {
                waits.add(delay);
                return false;
              },
              BUDGET),
          JSON);
    }

    @Test
    @DisplayName("Retry-After만큼 기다린 뒤 다시 시도한다")
    void honorsRetryAfter() {
      // given
      List<Duration> waits = new ArrayList<>();
      givenGeminiAnswers(FakeHttpServer.rateLimited(RETRY_AFTER.toSeconds()), ok(vectors(1, DIM)));

      // when
      embedderRecordingWaits(waits).embed(List.of("a"));

      // then
      assertThat(waits).containsExactly(RETRY_AFTER);
    }
  }

  @Nested
  @DisplayName("401이면")
  class Unauthorized {
    @BeforeEach
    void givenUnauthorized() {
      givenGeminiAnswers(status(UNAUTHORIZED, "{}"));
    }

    @Test
    @DisplayName("상태 코드를 밝혀 실패한다")
    void failsOnAuthenticationFailure() {
      // when·then
      assertThatThrownBy(() -> embedder.embed(List.of("a")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessage("Gemini embeddings 401");
    }

    @Test
    @DisplayName("실패로 끝나고, 재시도하지 않는다(요청 1회)")
    void doesNotRetryAuthenticationFailure() {
      // when
      Throwable failure = catchThrowable(() -> embedder.embed(List.of("a")));

      // then
      assertThat(failure).isInstanceOf(Embedder.EmbedderException.class);
      assertThat(requests()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("503 뒤 재시도 대기 중인 호출을 인터럽트하면(데몬 종료)")
  class InterruptedDuringRetryWait {
    final AtomicReference<Throwable> thrown = new AtomicReference<>();
    BackgroundThread caller;

    /** 503 뒤 실제 대기(제품 조립과 같은 waiter: 토큰·인터럽트로 깨어남)에 들어간 호출. */
    @BeforeEach
    void givenCallWaitingToRetry() {
      givenGeminiAnswers(status(UNAVAILABLE, "{}"));
      GeminiEmbedder waiting =
          new GeminiEmbedder(
              http.baseUrl(),
              API_KEY,
              executor((d, c) -> c.awaitCancellation(d), LONG_BUDGET),
              JSON);
      caller =
          BackgroundThread.start(
              "embed-caller",
              () -> {
                try {
                  waiting.embed(List.of("a"));
                } catch (RuntimeException e) {
                  thrown.set(e);
                }
              });
      Eventually.await(
          "첫 요청 뒤 재시도 대기",
          () -> requests() == 1 && caller.isWaitingIn(CancellationToken.class, "awaitCancellation"),
          WAIT_LIMIT);
    }

    @Test
    @DisplayName("호출이 끝난다")
    void callReturns() throws Exception {
      // when
      caller.interruptAndJoin(WAIT_LIMIT);

      // then
      assertThat(caller.isAlive()).isFalse();
    }

    @Test
    @DisplayName("취소 메시지의 EmbedderException으로 끝난다")
    void endsCancelled() throws Exception {
      // when
      caller.interruptAndJoin(WAIT_LIMIT);

      // then
      assertThat(thrown.get())
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessage("Gemini embeddings request failed: cancelled");
    }

    @Test
    @DisplayName("취소는 상태 코드가 없다")
    void cancellationHasNoStatus() throws Exception {
      // when
      caller.interruptAndJoin(WAIT_LIMIT);

      // then
      assertThat(thrown.get())
          .isInstanceOfSatisfying(
              Embedder.EmbedderException.class, e -> assertThat(e.status()).isEmpty());
    }

    @Test
    @DisplayName("더 시도하지 않는다")
    void doesNotRetry() throws Exception {
      // when
      caller.interruptAndJoin(WAIT_LIMIT);

      // then
      assertThat(requests()).isEqualTo(1);
    }
  }
}
