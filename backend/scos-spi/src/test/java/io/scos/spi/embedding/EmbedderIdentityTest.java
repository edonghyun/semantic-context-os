package io.scos.spi.embedding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 임베더 identity(stage2 §2.2): 공급자·모델·차원은 임베딩 세대의 재료라, 세대 문자열에 섞일 수 없는 값을 만들 때 거부한다. */
@DisplayName("임베더 identity는 세대에 쓸 수 있는 공급자·모델·차원만 받는다(stage2 §2.2)")
class EmbedderIdentityTest {
  static final String PROVIDER = "openai-compatible";
  static final String MODEL = "nomic-embed-text";
  static final int DIM = 768;

  @ParameterizedTest(name = "[{0}]")
  @NullAndEmptySource
  @ValueSource(strings = {"Gemini", "-gemini", "open ai", "open_ai", "gemini/v1"})
  @DisplayName("공급자 이름은 소문자·숫자·하이픈만 받는다")
  void rejectsProviderOutsideLowercaseDigitsHyphen(String provider) {
    // when·then
    assertThatThrownBy(() -> new EmbedderIdentity(provider, MODEL, DIM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "[{0}]")
  @NullAndEmptySource
  @ValueSource(strings = {" ", "text embedding", "model\t3", " leading"})
  @DisplayName("모델 이름은 비거나 공백을 담을 수 없다")
  void rejectsBlankOrSpacedModel(String model) {
    // when·then
    assertThatThrownBy(() -> new EmbedderIdentity(PROVIDER, model, DIM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "dim {0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("차원은 1 이상이다")
  void rejectsNonPositiveDim(int dim) {
    // when·then
    assertThatThrownBy(() -> new EmbedderIdentity(PROVIDER, MODEL, dim))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
