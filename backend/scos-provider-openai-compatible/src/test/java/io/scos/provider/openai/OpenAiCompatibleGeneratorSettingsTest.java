package io.scos.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OpenAI 호환 생성기 설정의 문자열 표현은 API 키를 가린다")
class OpenAiCompatibleGeneratorSettingsTest {
  static final String API_KEY = "openai-secret-key";
  static final String BASE_URL = "http://127.0.0.1:1/v1";
  static final String MODEL = "local-model";
  static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final String MASKED = "***";
  static final String ABSENT = "<none>";

  static String expected(String apiKey) {
    return "OpenAiCompatibleGeneratorSettings[baseUrl="
        + BASE_URL
        + ", model="
        + MODEL
        + ", apiKey="
        + apiKey
        + ", timeout="
        + TIMEOUT
        + "]";
  }

  @Test
  @DisplayName("키가 있으면 문자열에서 키 자리에 가림 표시를 두고, 나머지 필드는 보인다")
  void toStringMasksPresentApiKey() {
    // given
    OpenAiCompatibleGeneratorSettings settings =
        new OpenAiCompatibleGeneratorSettings(BASE_URL, MODEL, Optional.of(API_KEY), TIMEOUT);

    // then
    assertThat(settings.toString()).isEqualTo(expected(MASKED));
  }

  @Test
  @DisplayName("키가 없으면 문자열에서 키 자리에 없음 표시를 둔다")
  void toStringMarksAbsentApiKey() {
    // given
    OpenAiCompatibleGeneratorSettings settings =
        new OpenAiCompatibleGeneratorSettings(BASE_URL, MODEL, Optional.empty(), TIMEOUT);

    // then
    assertThat(settings.toString()).isEqualTo(expected(ABSENT));
  }
}
