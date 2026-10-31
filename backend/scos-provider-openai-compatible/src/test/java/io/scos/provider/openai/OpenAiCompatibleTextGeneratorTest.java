package io.scos.provider.openai;

import static io.scos.provider.openai.OpenAiCompatibleFakes.API_PATH;
import static io.scos.support.FakeHttpServer.ok;
import static io.scos.support.FakeHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.embedding.TextGenerator;
import io.scos.support.FakeHttpServer;
import io.scos.support.ProviderCallFakes;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 호환 /chat/completions 어댑터 계약(공개 배포 단계 5 T2, 재정렬 LLM): 요청 형식, choices[0].message.content, 오류
 * 매핑. 로컬 fake HTTP, 실 API 호출 없음.
 */
@DisplayName("OpenAI 호환 텍스트 생성 어댑터는 첫 선택지의 content를 돌려준다")
class OpenAiCompatibleTextGeneratorTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String API_KEY = "k";
  static final String MODEL = "llama3.2";
  static final String ENDPOINT = API_PATH + "/chat/completions";
  static final int MAX_TOKENS = 100;

  final FakeHttpServer http = ProviderCallFakes.startHttp();

  @AfterEach
  void stop() {
    http.close();
  }

  OpenAiCompatibleTextGenerator generator() {
    return new OpenAiCompatibleTextGenerator(
        http.baseUrl() + API_PATH, Optional.of(API_KEY), MODEL, ProviderCallFakes.executor(), JSON);
  }

  static String choice(String content) {
    return "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
        + content
        + "}}]}";
  }

  @Test
  @DisplayName("/chat/completions로 model·messages·max_tokens를 보낸다")
  void sendsModelMessagesMaxTokens() {
    // given
    http.on("POST", ENDPOINT, req -> ok(choice("\"ok\"")));

    // when
    generator().generate("프롬프트", MAX_TOKENS);

    // then
    assertThat(JSON.readTree(http.received().get(0).body()))
        .isEqualTo(
            JSON.readTree(
                "{\"model\":\""
                    + MODEL
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\"프롬프트\"}],\"max_tokens\":"
                    + MAX_TOKENS
                    + "}"));
  }

  @Test
  @DisplayName("choices[0].message.content를 돌려준다")
  void returnsFirstChoiceContent() {
    // given
    http.on("POST", ENDPOINT, req -> ok(choice("\"3,1\"")));

    // when
    String got = generator().generate("p", MAX_TOKENS);

    // then
    assertThat(got).isEqualTo("3,1");
  }

  @ParameterizedTest(name = "content {0}")
  @ValueSource(strings = {"null", "{\"nested\":1}", "[\"a\"]"})
  @DisplayName("content가 문자열이 아니면 GenerationException이다")
  void nonStringContentIsRejected(String content) {
    // given
    http.on("POST", ENDPOINT, req -> ok(choice(content)));

    // when·then
    assertThatThrownBy(() -> generator().generate("p", MAX_TOKENS))
        .isInstanceOf(TextGenerator.GenerationException.class);
  }

  @Test
  @DisplayName("비2xx면 상태만 담은 GenerationException이다")
  void httpErrorCarriesStatusOnly() {
    // given
    http.on(
        "POST",
        ENDPOINT,
        req ->
            status(400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"secret\"}}"));

    // when·then
    assertThatThrownBy(() -> generator().generate("p", MAX_TOKENS))
        .isInstanceOf(TextGenerator.GenerationException.class)
        .hasMessage("OpenAI-compatible API 400");
  }
}
