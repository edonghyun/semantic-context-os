package io.scos.spi.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 임베더 포트 계약({@link Embedder}): texts[i] → vectors[i], 벡터 길이는 dim, 빈 입력은 빈 목록, dim은 identity의 차원. 실제
 * 어댑터와 테스트 fake가 같은 계약을 통과한다.
 */
@DisplayName("임베더는 입력마다 dim 길이의 벡터 하나를 같은 순서로 돌려준다")
public abstract class EmbedderContract {
  /** 서로 다른 입력 셋(한글·영문·빈 문자열 아님). */
  protected static final List<String> TEXTS = List.of("정산 마감 절차", "refund policy", "배포 회고");

  protected Embedder embedder;

  /** 이 테스트만 쓰는 새 임베더(외부 공급자는 가짜 서버 위에서). */
  protected abstract Embedder newEmbedder();

  @BeforeEach
  void setUpContract() {
    embedder = newEmbedder();
  }

  @Test
  @DisplayName("입력 수만큼 벡터를 돌려준다")
  void oneVectorPerText() {
    // when
    List<double[]> vectors = embedder.embed(TEXTS);

    // then
    assertThat(vectors).hasSize(TEXTS.size());
  }

  @Test
  @DisplayName("벡터마다 길이가 dim이다")
  void vectorsHaveDim() {
    // when
    List<double[]> vectors = embedder.embed(TEXTS);

    // then
    assertThat(vectors).allSatisfy(v -> assertThat(v).hasSize(embedder.dim()));
  }

  @Test
  @DisplayName("빈 입력이면 빈 목록이다")
  void emptyInputGivesEmptyList() {
    // when
    List<double[]> vectors = embedder.embed(List.of());

    // then
    assertThat(vectors).isEmpty();
  }

  @Test
  @DisplayName("dim은 identity의 차원이다")
  void dimIsIdentityDim() {
    // when
    int dim = embedder.dim();

    // then
    assertThat(dim).isEqualTo(embedder.identity().dim());
  }
}
