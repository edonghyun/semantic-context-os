package io.scos.provider.gemini;

import static io.scos.support.FakeHttpServer.ok;
import static io.scos.support.FakeHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.embedding.TextGenerator;
import io.scos.support.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini generateContent 어댑터 계약(경로가 등록된 로컬 fake HTTP, 실 API 호출 없음): 요청 형식(flash는 thinkingBudget 0),
 * parts 결합(thought 제외), 오류 매핑.
 */
@DisplayName("Gemini 텍스트 생성 어댑터는 TS와 같은 요청을 보내고, text part만 잇는다")
class GeminiTextGeneratorTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String API_KEY = "k";
  static final String FLASH = "gemini-2.5-flash";
  static final String PRO = "gemini-2.5-pro";
  static final int MAX_TOKENS = 100;
  static final int SMALL_MAX_TOKENS = 10;
  final FakeHttpServer http = GeminiFakes.startHttp();

  @AfterEach
  void stop() {
    http.assertNoUnmatched();
    http.close();
  }

  static String endpoint(String model) {
    return "/v1beta/models/" + model + ":generateContent";
  }

  static String parts(String json) {
    return "{\"candidates\":[{\"content\":{\"parts\":[" + json + "]}}]}";
  }

  /** 첫 요청의 본문. */
  JsonNode sentBody() {
    return JSON.readTree(http.received().get(0).body());
  }

  GeminiTextGenerator generator(String model) {
    return new GeminiTextGenerator(http.baseUrl(), API_KEY, model, GeminiFakes.executor(), JSON);
  }

  @Nested
  @DisplayName("pro 모델이 text part 하나로 답하면")
  class ProAnswers {
    @BeforeEach
    void givenProAnswersOk() {
      http.on("POST", endpoint(PRO), req -> ok(parts("{\"text\":\"ok\"}")));
    }

    @Test
    @DisplayName("키는 x-goog-api-key 헤더로 보낸다")
    void sendsKeyInHeader() {
      // when
      generator(PRO).generate("프롬프트", MAX_TOKENS);

      // then
      assertThat(http.received().get(0).header("x-goog-api-key")).isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("키를 쿼리스트링에 싣지 않는다")
    void sendsKeyInHeaderOnly() {
      // when
      generator(PRO).generate("프롬프트", MAX_TOKENS);

      // then
      assertThat(http.received().get(0).query()).doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("요청 본문은 TS와 같은 형식(contents.parts.text, maxOutputTokens)이다")
    void requestBodyMatchesTs() {
      // when
      generator(PRO).generate("프롬프트", MAX_TOKENS);

      // then
      JsonNode body = sentBody();
      assertThat(body.get("contents").get(0).get("parts").get(0).get("text").asString())
          .isEqualTo("프롬프트");
      assertThat(body.get("generationConfig").get("maxOutputTokens").asInt()).isEqualTo(MAX_TOKENS);
    }

    @Test
    @DisplayName("thinkingConfig를 보내지 않는다")
    void proKeepsThinking() {
      // when
      generator(PRO).generate("p", SMALL_MAX_TOKENS);

      // then
      JsonNode config = sentBody().get("generationConfig");
      assertThat(config.has("thinkingConfig")).isFalse();
    }
  }

  @Nested
  @DisplayName("flash 모델로 생성하면")
  class Flash {
    @Test
    @DisplayName("응답 text part를 thought part 없이 순서대로 이어 붙인다")
    void joinsTextPartsExcludingThought() {
      // given
      http.on(
          "POST",
          endpoint(FLASH),
          req ->
              ok(parts("{\"text\":\"생각\",\"thought\":true},{\"text\":\"3,\"},{\"text\":\"1\"}")));

      // when
      String got = generator(FLASH).generate("프롬프트", MAX_TOKENS);

      // then
      assertThat(got).isEqualTo("3,1");
    }

    @Test
    @DisplayName("thinkingBudget 0을 보낸다")
    void flashDisablesThinking() {
      // given
      http.on("POST", endpoint(FLASH), req -> ok(parts("{\"text\":\"ok\"}")));

      // when
      generator(FLASH).generate("p", SMALL_MAX_TOKENS);

      // then: generationConfig.thinkingConfig.thinkingBudget
      JsonNode config = sentBody().get("generationConfig");
      assertThat(config.get("thinkingConfig").get("thinkingBudget").asInt()).isZero();
    }
  }

  @Nested
  @DisplayName("응답이 비2xx이거나 기대한 모양이 아니면")
  class BadResponses {
    @Test
    @DisplayName("비2xx 응답의 본문은 GenerationException 메시지에 싣지 않는다")
    void httpErrorHidesBody() {
      // given
      http.on("POST", endpoint(FLASH), req -> status(503, "{\"error\":\"secret\"}"));

      // when·then: 메시지는 'Gemini API <status>'
      assertThatThrownBy(() -> generator(FLASH).generate("p", SMALL_MAX_TOKENS))
          .isInstanceOf(TextGenerator.GenerationException.class)
          .hasMessage("Gemini API 503")
          .message()
          .doesNotContain("secret");
    }

    @Test
    @DisplayName("비텍스트 part(text가 객체)는 GenerationException(non-text)")
    void nonTextPartIsRejected() {
      // given
      http.on("POST", endpoint(FLASH), req -> ok(parts("{\"text\":{\"nested\":1}}")));

      // when·then
      assertThatThrownBy(() -> generator(FLASH).generate("p", SMALL_MAX_TOKENS))
          .isInstanceOf(TextGenerator.GenerationException.class)
          .hasMessageContaining("non-text");
    }

    @Test
    @DisplayName("JSON이 아닌 응답은 GenerationException")
    void malformedResponse() {
      // given
      http.on("POST", endpoint(FLASH), req -> ok("not json"));

      // when·then
      assertThatThrownBy(() -> generator(FLASH).generate("p", SMALL_MAX_TOKENS))
          .isInstanceOf(TextGenerator.GenerationException.class);
    }

    @Test
    @DisplayName("candidates가 비면 빈 문자열이다(재정렬 단계가 형식 오류로 폴백한다)")
    void emptyCandidatesIsEmptyString() {
      // given
      http.on("POST", endpoint(FLASH), req -> ok("{\"candidates\":[]}"));

      // when
      String got = generator(FLASH).generate("p", SMALL_MAX_TOKENS);

      // then
      assertThat(got).isEmpty();
    }
  }

  @Nested
  @DisplayName("503 뒤 2xx면")
  class ServerErrorThenSuccess {
    @BeforeEach
    void givenUnavailableThenOk() {
      http.on(
          "POST",
          endpoint(FLASH),
          FakeHttpServer.inOrder(status(503, "{}"), ok(parts("{\"text\":\"ok\"}"))));
    }

    @Test
    @DisplayName("두 번째 요청의 생성 결과를 돌려준다")
    void retriesOnServerError() {
      // when
      String got = generator(FLASH).generate("p", SMALL_MAX_TOKENS);

      // then
      assertThat(got).isEqualTo("ok");
    }

    @Test
    @DisplayName("다시 요청한다")
    void retriesServerErrorOnce() {
      // when
      generator(FLASH).generate("p", SMALL_MAX_TOKENS);

      // then
      assertThat(http.received()).hasSize(2);
    }
  }
}
