package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.ProvidersConfiguration.GeminiProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Gemini 접속 설정의 문자열 표현은 API 키를 가린다")
class GeminiPropertiesTest {
  static final String API_KEY = "gemini-secret-key";
  static final String BASE_URL = "http://127.0.0.1:1";
  static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final String MASKED = "***";
  static final String ABSENT = "<none>";

  static String expected(String apiKey) {
    return "GeminiProperties[apiKey="
        + apiKey
        + ", baseUrl="
        + BASE_URL
        + ", timeout="
        + TIMEOUT
        + "]";
  }

  @Test
  @DisplayName("키가 있으면 문자열에서 키 자리에 가림 표시를 두고, 나머지 필드는 보인다")
  void toStringMasksPresentApiKey() {
    // given
    GeminiProperties props = new GeminiProperties(API_KEY, BASE_URL, TIMEOUT);

    // then
    assertThat(props.toString()).isEqualTo(expected(MASKED));
  }

  @Test
  @DisplayName("키가 없으면 문자열에서 키 자리에 없음 표시를 둔다")
  void toStringMarksAbsentApiKey() {
    // given
    GeminiProperties props = new GeminiProperties(null, BASE_URL, TIMEOUT);

    // then
    assertThat(props.toString()).isEqualTo(expected(ABSENT));
  }
}
