package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.VectorIndex;
import io.scos.spi.store.VectorIndexContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * P-3(정확 검색): pgvector 어댑터가 계약을 지키고, TS 합성 fixture의 `searchVectors` 결과와 동점군 밖 순서 동일·상대 오차 1e-6 안인지.
 */
@Tag("db")
@DisplayName("pgvector 벡터 색인은 계약을 지키고 TS 정확 검색과 같다(ADR-0014 P-3)")
class PostgresVectorIndexTest {
  @BeforeEach
  void clean() {
    PostgresSupport.truncateAll();
  }

  @Nested
  class Contract extends VectorIndexContract {
    @Override
    protected VectorIndex newIndex() {
      return new PostgresVectorIndex(new NamedParameterJdbcTemplate(PostgresSupport.dataSource()));
    }
  }

  @Nested
  @DisplayName("TS 합성 fixture의 13질의를 다시 돌리면")
  class TsReference {
    /** float4 저장이 허용하는 점수 상대 오차. */
    static final double MAX_RELATIVE_ERROR = 1e-6;

    final VectorIndex index =
        new PostgresVectorIndex(new NamedParameterJdbcTemplate(PostgresSupport.dataSource()));
    final TsVectorReference reference = TsVectorReference.load();

    TsVectorReference.Comparison comparison;

    /** 상황의 실행(fixture 적재 → 13질의 비교)을 기록만 한다. 비교한 hit이 없으면 아래 판정이 공허하다. */
    @BeforeEach
    void queryFixture() {
      reference.indexInto(index);
      comparison = reference.compareWith(index);
      if (comparison.comparedHits() <= 0)
        throw new IllegalStateException("given 준비 실패: 비교한 hit이 있어야 한다");
    }

    @Test
    @DisplayName("질의마다 TS와 같은 수의 결과를 낸다")
    void sameHitCounts() {
      // then
      assertThat(comparison.queriesWithDifferentHitCount()).isEmpty();
    }

    @Test
    @DisplayName("동점군 밖의 순서는 TS와 같다")
    void sameOrderOutsideTies() {
      // then
      assertThat(comparison.mismatchesOutsideTies()).isZero();
    }

    @Test
    @DisplayName("점수 상대 오차는 1e-6 안이다(float4 저장)")
    void scoresWithinFloat4Error() {
      // then
      assertThat(comparison.maxRelativeError()).isLessThan(MAX_RELATIVE_ERROR);
    }
  }
}
