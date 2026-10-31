package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemorySpaceMeta;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 엔진이 쓰는 임베딩 세대(stage2 §3.1): 임베더 identity + 엔진의 임베딩 입력 형식. 공간에 저장된 세대가 설정과 다르거나 재임베딩이 끝나지 않았으면
 * 거부하고 {@code scos reembed}를 안내한다(§3.3).
 */
@DisplayName("임베딩 세대는 임베더로 정하고 공간에 저장된 세대와 대조한다(stage2 §3.3)")
class EmbeddingGenerationCheckTest {
  /** 운영 Gemini와 같은 identity — 벡터는 쓰지 않는다. */
  static final Embedder GEMINI =
      new Embedder() {
        @Override
        public EmbedderIdentity identity() {
          return new EmbedderIdentity("gemini", "gemini-embedding-001", 768);
        }

        @Override
        public List<double[]> embed(List<String> texts) {
          throw new UnsupportedOperationException("세대 계산은 임베딩하지 않는다");
        }
      };

  @Test
  @DisplayName("임베더 identity와 엔진 입력 형식으로 세대를 만든다")
  void generationFromIdentityAndInputFormat() {
    // when
    String generation = EmbeddingGenerationCheck.generationOf(GEMINI).value();

    // then
    assertThat(generation).isEqualTo("gemini:gemini-embedding-001:768:title+aliases+content");
  }

  @Nested
  @DisplayName("공간에 저장된 세대를 설정 세대와 대조하면")
  class Verify {
    static final SpaceId LEGACY = new SpaceId("legacy");
    static final SpaceId TEAM = new SpaceId("team");
    static final EmbeddingGeneration CONFIGURED =
        EmbeddingGeneration.parse("gemini:gemini-embedding-001:768:title+aliases+content");
    static final String OTHER = "openai-compatible:nomic-embed-text:v1.5:768:title+aliases+content";

    final MemorySpaceMeta meta = new MemorySpaceMeta();

    void givenStored(SpaceId space, String generation) {
      meta.set(space, "embedding:version", generation);
    }

    @Test
    @DisplayName("저장된 세대가 없으면 통과한다")
    void passesWithoutStoredGeneration() {
      // when·then
      assertThatCode(() -> EmbeddingGenerationCheck.verify(meta, List.of(LEGACY), CONFIGURED))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("저장된 세대가 설정과 같으면 통과한다")
    void passesWhenStoredMatches() {
      // given
      givenStored(LEGACY, CONFIGURED.value());

      // when·then
      assertThatCode(() -> EmbeddingGenerationCheck.verify(meta, List.of(LEGACY), CONFIGURED))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다르면 공간·저장값·설정값을 담은 예외로 거부한다")
    void rejectsDifferentGeneration() {
      // given
      givenStored(LEGACY, OTHER);

      // when
      var thrown =
          catchThrowableOfType(
              EmbeddingGenerationMismatchException.class,
              () -> EmbeddingGenerationCheck.verify(meta, List.of(LEGACY), CONFIGURED));

      // then
      assertThat(thrown)
          .hasMessage(
              "space legacy: stored embedding generation "
                  + OTHER
                  + ", configured gemini:gemini-embedding-001:768:title+aliases+content."
                  + " Run 'scos reembed --space=legacy' or restore SCOS_EMBEDDER_* settings.")
          .extracting(
              EmbeddingGenerationMismatchException::space,
              EmbeddingGenerationMismatchException::stored,
              EmbeddingGenerationMismatchException::configured,
              EmbeddingGenerationMismatchException::reembedPending)
          .containsExactly(LEGACY, OTHER, CONFIGURED, false);
    }

    @Test
    @DisplayName("pending이면 설정과 같아도 거부하고 reembed 재실행을 안내한다")
    void rejectsPendingEvenWhenTargetMatches() {
      // given
      givenStored(LEGACY, "pending:" + CONFIGURED.value());

      // when
      var thrown =
          catchThrowableOfType(
              EmbeddingGenerationMismatchException.class,
              () -> EmbeddingGenerationCheck.verify(meta, List.of(LEGACY), CONFIGURED));

      // then
      assertThat(thrown)
          .hasMessage(
              "space legacy: re-embedding to gemini:gemini-embedding-001:768:title+aliases+content"
                  + " did not finish. Re-run 'scos reembed --space=legacy'.")
          .extracting(EmbeddingGenerationMismatchException::reembedPending)
          .isEqualTo(true);
    }

    @Test
    @DisplayName("여러 공간 중 하나라도 다르면 거부한다")
    void rejectsWhenAnySpaceDiffers() {
      // given
      givenStored(LEGACY, CONFIGURED.value());
      givenStored(TEAM, OTHER);

      // when
      var thrown =
          catchThrowableOfType(
              EmbeddingGenerationMismatchException.class,
              () -> EmbeddingGenerationCheck.verify(meta, List.of(LEGACY, TEAM), CONFIGURED));

      // then
      assertThat(thrown).extracting(EmbeddingGenerationMismatchException::space).isEqualTo(TEAM);
    }
  }
}
