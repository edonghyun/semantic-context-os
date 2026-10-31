package io.scos.spi.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 임베딩 세대(stage2 §3.1): {@code <provider>:<model>:<dim>:<input-format>}. 모델 이름에는 콜론이 들어갈 수 있다. */
@DisplayName("임베딩 세대는 공급자·모델·차원·입력 형식을 한 문자열로 오간다(stage2 §3.1)")
class EmbeddingGenerationTest {
  static final String TAGGED_MODEL_GENERATION =
      "openai-compatible:nomic-embed-text:v1.5:768:title+aliases+content";

  @Test
  @DisplayName("공급자는 첫 콜론 앞, 입력 형식은 마지막 콜론 뒤, 차원은 그 앞 칸에서 읽는다")
  void parsesFieldsAroundModelWithColon() {
    // when
    EmbeddingGeneration generation = EmbeddingGeneration.parse(TAGGED_MODEL_GENERATION);

    // then
    assertThat(generation)
        .isEqualTo(
            new EmbeddingGeneration(
                "openai-compatible", "nomic-embed-text:v1.5", 768, "title+aliases+content"));
  }

  @Test
  @DisplayName("value는 parse의 역이다")
  void valueIsInverseOfParse() {
    // when
    String value = EmbeddingGeneration.parse(TAGGED_MODEL_GENERATION).value();

    // then
    assertThat(value).isEqualTo(TAGGED_MODEL_GENERATION);
  }

  @ParameterizedTest(name = "[{0}]")
  @ValueSource(
      strings = {
        "",
        "gemini:768:title+aliases+content",
        "gemini::768:title+aliases+content",
        "gemini:gemini-embedding-001:wide:title+aliases+content",
        "gemini:gemini-embedding-001:+768:title+aliases+content",
        "gemini:gemini-embedding-001:0:title+aliases+content",
        "gemini:gemini-embedding-001:99999999999:title+aliases+content",
        "gemini:gemini-embedding-001:768:"
      })
  @DisplayName("칸이 모자라거나 차원이 숫자가 아니면 거부한다")
  void rejectsMissingFieldsOrNonNumericDim(String value) {
    // when·then
    assertThatThrownBy(() -> EmbeddingGeneration.parse(value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "[{0}]")
  @ValueSource(
      strings = {
        "Gemini:gemini-embedding-001:768:title+aliases+content",
        "gemini:gemini embedding:768:title+aliases+content",
        "gemini:gemini-embedding-001:768:Title+aliases+content",
        "gemini:gemini-embedding-001:768:title_content"
      })
  @DisplayName("공급자·모델·입력 형식이 세대 문자열 규칙을 어기면 거부한다")
  void rejectsFieldsOutsideGenerationRules(String value) {
    // when·then
    assertThatThrownBy(() -> EmbeddingGeneration.parse(value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("공급자 이름 pending은 재임베딩 표시와 헷갈리므로 거부한다")
  void rejectsPendingProvider() {
    // when·then
    assertThatThrownBy(
            () ->
                EmbeddingGeneration.parse(
                    "pending:gemini:gemini-embedding-001:768:title+aliases+content"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("옛 형식 GeminiEmbedder:768:title+aliases+content는 거부한다")
  void rejectsLegacyLabel() {
    // when·then
    assertThatThrownBy(() -> EmbeddingGeneration.parse("GeminiEmbedder:768:title+aliases+content"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
