package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("임베더 설정의 문자열 표현은 API 키를 가린다")
class EmbedderPropertiesTest {
  static final String PROVIDER = "openai-compatible";
  static final String BASE_URL = "http://127.0.0.1:1/v1";
  static final String MODEL = "local-model";
  static final int DIM = 1024;
  static final String API_KEY = "embedder-secret-key";
  static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final String MASKED = "***";
  static final String ABSENT = "<none>";

  static String expected(String apiKey) {
    return "EmbedderProperties[provider="
        + PROVIDER
        + ", baseUrl="
        + BASE_URL
        + ", model="
        + MODEL
        + ", dim="
        + DIM
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
    EmbedderProperties props =
        new EmbedderProperties(PROVIDER, BASE_URL, MODEL, DIM, API_KEY, TIMEOUT);

    // then
    assertThat(props.toString()).isEqualTo(expected(MASKED));
  }

  @Test
  @DisplayName("키가 없으면 문자열에서 키 자리에 없음 표시를 둔다")
  void toStringMarksAbsentApiKey() {
    // given
    EmbedderProperties props =
        new EmbedderProperties(PROVIDER, BASE_URL, MODEL, DIM, null, TIMEOUT);

    // then
    assertThat(props.toString()).isEqualTo(expected(ABSENT));
  }
}
