package io.scos.provider.openai;

import static io.scos.provider.openai.OpenAiCompatibleFakes.API_PATH;
import static io.scos.support.FakeHttpServer.inOrder;
import static io.scos.support.FakeHttpServer.ok;
import static io.scos.support.FakeHttpServer.status;
import static io.scos.support.ProviderCallFakes.BUDGET;
import static io.scos.support.ProviderCallFakes.MAX_ATTEMPTS;
import static io.scos.support.ProviderCallFakes.executor;
import static io.scos.support.ProviderCallFakes.startHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.support.FakeHttpServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 호환 /embeddings 어댑터 계약(공개 배포 단계 5 T2, Review Focus 1): 경로가 등록된 로컬 fake HTTP로 base URL·요청
 * 본문·인증 헤더·index 정렬·배치·검증·재시도. 실 API 호출 없음.
 */
@DisplayName("OpenAI 호환 임베딩 어댑터는 100개씩 보내고 index 순서로 검증한다")
class OpenAiCompatibleEmbedderTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String API_KEY = "test-key";
  static final String MODEL = "nomic-embed-text";
  static final String ENDPOINT = API_PATH + "/embeddings";
  static final int DIM = 4;
  static final int WRONG_DIM = 3;
  static final int BATCHED_INPUTS = 250;
  static final Duration RETRY_AFTER = Duration.ofSeconds(7);
  static final int RATE_LIMITED = 429;
  static final int UNAVAILABLE = 503;
  static final int UNAUTHORIZED = 401;

  final FakeHttpServer http = startHttp();

  @AfterEach
  void stop() {
    http.close();
  }

  OpenAiCompatibleEmbedder embedder(String baseUrl, Optional<String> apiKey) {
    return new OpenAiCompatibleEmbedder(baseUrl, apiKey, MODEL, DIM, executor(), JSON);
  }

  OpenAiCompatibleEmbedder embedder() {
    return embedder(http.baseUrl() + API_PATH, Optional.of(API_KEY));
  }

  /** data[i]는 index i, 값은 전부 i+1인 dim 차원 벡터. order는 data에 싣는 index 순서다. */
  static String data(int dim, int... order) {
    StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");
    for (int i = 0; i < order.length; i++) {
      sb.append(i > 0 ? "," : "")
          .append("{\"object\":\"embedding\",\"index\":")
          .append(order[i])
          .append(",\"embedding\":[");
      for (int d = 0; d < dim; d++) sb.append(d > 0 ? "," : "").append(order[i] + 1).append(".0");
      sb.append("]}");
    }
    return sb.append("],\"model\":\"" + MODEL + "\"}").toString();
  }

  /** index 0..n-1을 순서대로 담은 응답. */
  static String inIndexOrder(int n, int dim) {
    return data(dim, IntStream.range(0, n).toArray());
  }

  /** 요청의 input 개수만큼 벡터로 답한다. */
  void givenServerAnswersEveryInput() {
    http.on(
        "POST",
        ENDPOINT,
        req -> ok(inIndexOrder(JSON.readTree(req.body()).get("input").size(), DIM)));
  }

  void givenServerAnswers(FakeHttpServer.Response... responses) {
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
  @DisplayName("요청은")
  class Requests {
    @Test
    @DisplayName("base URL 끝의 슬래시를 떼고 /embeddings로 보낸다")
    void stripsTrailingSlashAndAppendsEmbeddings() {
      // given
      givenServerAnswersEveryInput();

      // when
      embedder(http.baseUrl() + API_PATH + "/", Optional.of(API_KEY)).embed(List.of("a"));

      // then
      assertThat(http.received().get(0).path()).isEqualTo("/v1/embeddings");
    }

    @Test
    @DisplayName("요청 본문은 model과 input 배열만 담는다")
    void bodyCarriesModelAndInputOnly() {
      // given
      givenServerAnswersEveryInput();

      // when
      embedder().embed(List.of("a", "b"));

      // then
      assertThat(sentBody())
          .isEqualTo(JSON.readTree("{\"model\":\"" + MODEL + "\",\"input\":[\"a\",\"b\"]}"));
    }

    @Test
    @DisplayName("키가 있으면 Bearer 인증 헤더를 보낸다")
    void sendsBearerWhenKeyPresent() {
      // given
      givenServerAnswersEveryInput();

      // when
      embedder().embed(List.of("a"));

      // then
      assertThat(http.received().get(0).header("authorization")).isEqualTo("Bearer " + API_KEY);
    }

    @Test
    @DisplayName("키가 없으면 인증 헤더를 보내지 않는다")
    void sendsNoAuthorizationWithoutKey() {
      // given
      givenServerAnswersEveryInput();

      // when
      embedder(http.baseUrl() + API_PATH, Optional.empty()).embed(List.of("a"));

      // then
      assertThat(http.received().get(0).header("authorization")).isNull();
    }

    @ParameterizedTest(name = "키 [{0}]")
    @ValueSource(strings = {"", "  "})
    @DisplayName("빈 키는 인증 헤더를 보내지 않는다")
    void sendsNoAuthorizationForBlankKey(String blankKey) {
      // given
      givenServerAnswersEveryInput();

      // when
      embedder(http.baseUrl() + API_PATH, Optional.of(blankKey)).embed(List.of("a"));

      // then
      assertThat(http.received().get(0).header("authorization")).isNull();
    }

    @Test
    @DisplayName("100개를 넘는 입력은 100개씩 나눠 보낸다")
    void batchesByHundred() {
      // given
      givenServerAnswersEveryInput();
      List<String> texts = IntStream.range(0, BATCHED_INPUTS).mapToObj(i -> "t" + i).toList();

      // when
      embedder().embed(texts);

      // then
      assertThat(http.received())
          .extracting(r -> JSON.readTree(r.body()).get("input").size())
          .containsExactly(100, 100, 50);
    }

    @Test
    @DisplayName("빈 입력은 요청 없이 빈 목록이다")
    void emptyInputMakesNoRequest() {
      // when
      List<double[]> got = embedder().embed(List.of());

      // then
      assertThat(got).isEmpty();
      assertThat(requests()).isZero();
    }
  }

  @Test
  @DisplayName("응답을 index 순서로 돌려준다")
  void returnsVectorsInIndexOrder() {
    // given
    givenServerAnswers(ok(data(DIM, 2, 0, 1)));

    // when
    List<double[]> got = embedder().embed(List.of("a", "b", "c"));

    // then
    assertThat(got).extracting(v -> v[0]).containsExactly(1.0, 2.0, 3.0);
  }

  @Test
  @DisplayName("identity는 openai-compatible·설정 모델·설정 차원이다")
  void identityIsProviderModelDim() {
    // when
    EmbedderIdentity identity = embedder().identity();

    // then
    assertThat(identity).isEqualTo(new EmbedderIdentity("openai-compatible", MODEL, DIM));
  }

  @Nested
  @DisplayName("응답이 잘못되면")
  class BadResponses {
    @Test
    @DisplayName("벡터 길이가 설정 차원과 다르면 dim mismatch 예외다")
    void dimMismatch() {
      // given
      givenServerAnswers(ok(inIndexOrder(1, WRONG_DIM)));

      // when·then
      assertThatThrownBy(() -> embedder().embed(List.of("a")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessage("OpenAI-compatible embeddings dim mismatch: 3 != 4");
    }

    @Test
    @DisplayName("벡터 수가 입력 수와 다르면 count mismatch 예외다")
    void countMismatch() {
      // given
      givenServerAnswers(ok(inIndexOrder(1, DIM)));

      // when·then
      assertThatThrownBy(() -> embedder().embed(List.of("a", "b")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessage("OpenAI-compatible embeddings count mismatch: 1 != 2");
    }

    @ParameterizedTest(name = "index {0}")
    @ValueSource(ints = {-1, 2})
    @DisplayName("index가 입력 범위를 벗어나면 형식 오류다(조용한 정렬 어긋남 금지)")
    void indexOutOfRange(int index) {
      // given
      givenServerAnswers(ok(data(DIM, 0, index)));

      // when·then
      assertThatThrownBy(() -> embedder().embed(List.of("a", "b")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessageContaining("malformed");
    }

    @Test
    @DisplayName("같은 index가 두 번 오면 형식 오류다")
    void duplicateIndex() {
      // given
      givenServerAnswers(ok(data(DIM, 0, 0)));

      // when·then
      assertThatThrownBy(() -> embedder().embed(List.of("a", "b")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessageContaining("malformed");
    }

    @ParameterizedTest(name = "본문 {0}")
    @ValueSource(strings = {"not json", "{\"unexpected\":1}"})
    @DisplayName("형식 오류(JSON 아님·data 없음)는 실패한다")
    void malformedResponse(String body) {
      // given
      givenServerAnswers(ok(body));

      // when·then
      assertThatThrownBy(() -> embedder().embed(List.of("a")))
          .isInstanceOf(Embedder.EmbedderException.class)
          .hasMessageContaining("malformed");
    }

    @Test
    @DisplayName("비2xx면 상태와 식별자 코드만 담고 본문 문장은 싣지 않는다")
    void httpErrorCarriesStatusAndIdentifierOnly() {
      // given
      givenServerAnswers(
          status(
              400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"secret text\"}}"));

      // when
      Throwable failure = catchThrowable(() -> embedder().embed(List.of("a")));

      // then
      assertThat(failure)
          .isInstanceOfSatisfying(
              Embedder.EmbedderException.class, e -> assertThat(e.status()).hasValue(400))
          .hasMessage("OpenAI-compatible embeddings 400: invalid_request_error")
          .message()
          .doesNotContain("secret text");
    }
  }

  @Nested
  @DisplayName("재시도와 분류는")
  class RetriesAndClassification {
    @Test
    @DisplayName("429면 Retry-After만큼 기다린 뒤 다시 시도한다")
    void honorsRetryAfter() {
      // given
      List<Duration> waits = new ArrayList<>();
      givenServerAnswers(
          FakeHttpServer.rateLimited(RETRY_AFTER.toSeconds()), ok(inIndexOrder(1, DIM)));
      OpenAiCompatibleEmbedder recording =
          new OpenAiCompatibleEmbedder(
              http.baseUrl() + API_PATH,
              Optional.of(API_KEY),
              MODEL,
              DIM,
              executor(
                  (delay, cancel) -> {
                    waits.add(delay);
                    return false;
                  },
                  BUDGET),
              JSON);

      // when
      recording.embed(List.of("a"));

      // then
      assertThat(waits).containsExactly(RETRY_AFTER);
    }

    @Test
    @DisplayName("5xx면 다시 요청한다")
    void retriesServerError() {
      // given
      givenServerAnswers(status(UNAVAILABLE, "{}"), ok(inIndexOrder(1, DIM)));

      // when
      embedder().embed(List.of("a"));

      // then
      assertThat(requests()).isEqualTo(2);
    }

    @Test
    @DisplayName("5xx가 계속되면 최대 시도 수까지 요청하고 그 상태로 실패한다")
    void serverErrorExhaustsAttempts() {
      // given
      givenServerAnswers(status(UNAVAILABLE, "{}"));

      // when
      Throwable failure = catchThrowable(() -> embedder().embed(List.of("a")));

      // then
      assertThat(failure).hasMessage("OpenAI-compatible embeddings " + UNAVAILABLE);
      assertThat(requests()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("401이면 다시 요청하지 않는다")
    void doesNotRetryUnauthorized() {
      // given
      givenServerAnswers(status(UNAUTHORIZED, "{}"));

      // when
      Throwable failure = catchThrowable(() -> embedder().embed(List.of("a")));

      // then
      assertThat(failure).isInstanceOf(Embedder.EmbedderException.class);
      assertThat(requests()).isEqualTo(1);
    }

    @Test
    @DisplayName("전송 오류는 상태 없이 실패 종류만 밝힌다")
    void transportErrorHasKindOnly() {
      // given: 닫힌 서버(연결 거부)
      FakeHttpServer closed = startHttp();
      String deadBaseUrl = closed.baseUrl() + API_PATH;
      closed.abandon();

      // when
      Throwable failure =
          catchThrowable(() -> embedder(deadBaseUrl, Optional.of(API_KEY)).embed(List.of("a")));

      // then
      assertThat(failure)
          .isInstanceOfSatisfying(
              Embedder.EmbedderException.class, e -> assertThat(e.status()).isEmpty())
          .hasMessage("OpenAI-compatible embeddings request failed: dependency_unavailable");
    }
  }
}
