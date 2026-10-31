package io.scos.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Gemini 설정의 문자열 표현은 API 키를 가린다")
class GeminiSettingsTest {
  static final String API_KEY = "gemini-secret-key";
  static final String BASE_URL = "http://127.0.0.1:1";
  static final String MODEL = "gemini-embedding-002";
  static final int DIM = 1536;
  static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final String MASKED = "***";

  @Test
  @DisplayName("문자열로 바꾸면 키 자리에 가림 표시를 두고, 나머지 필드는 보인다")
  void toStringMasksApiKey() {
    // given
    GeminiSettings settings = new GeminiSettings(API_KEY, BASE_URL, MODEL, DIM, TIMEOUT);

    // then
    assertThat(settings.toString())
        .isEqualTo(
            "GeminiSettings[apiKey="
                + MASKED
                + ", baseUrl="
                + BASE_URL
                + ", model="
                + MODEL
                + ", dim="
                + DIM
                + ", timeout="
                + TIMEOUT
                + "]");
  }
}
