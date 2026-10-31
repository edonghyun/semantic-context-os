package io.scos.engine.internal.projection;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.support.projection.EmbeddingCounts;
import io.scos.spi.store.ContextUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("키워드 전용 투영은 임베딩 작업을 하지 않는다(A2)")
class KeywordOnlyUnitEmbeddingTest {
  final UnitEmbedding embedding = new KeywordOnlyUnitEmbedding();
  final UnitEmbedding.Tally tally = new UnitEmbedding.Tally();

  @Test
  @DisplayName("빈 유닛 목록에는 빈 결과를 돌려준다")
  void acceptsEmptyUnits() {
    // given: 유닛이 없다

    // when
    List<ContextUnit> attached = embedding.attach(List.of(), tally);

    // then
    assertThat(attached).isEmpty();
  }

  @Test
  @DisplayName("유닛이 있어도 캐시 적중과 새 임베딩 개수는 모두 0이다")
  void reportsNoEmbeddingWork() {
    // given
    List<ContextUnit> units = List.of(unit("a").build());

    // when
    embedding.attach(units, tally);

    // then
    assertThat(tally.counts()).isEqualTo(new EmbeddingCounts(0, 0));
  }

  @Test
  @DisplayName("입력 유닛의 순서를 유지하며 기존 벡터를 제거한다")
  void preservesUnitsWithoutVectors() {
    // given
    List<ContextUnit> units = List.of(unit("a").embedding(1, 2).build(), unit("b").build());

    // when
    List<ContextUnit> attached = embedding.attach(units, tally);

    // then
    assertThat(attached).containsExactly(unit("a").build(), unit("b").build());
  }
}
