package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("압축 대안 프로브는 제한 범위에서만 탐침을 실행한다")
class VectorCompactionProbeTest {
  private static final SpaceId SPACE = new SpaceId("vector-compaction-probe");
  private static final int TOP_K = 1;
  private static final int DIMENSIONS = 768;
  private static final SearchFilter UNRESTRICTED =
      new SearchFilter(true, null, AccessScope.singleUser());
  private final VectorCompactionProbe probe =
      new VectorCompactionProbe(PostgresSupport.dataSource());

  @Test
  @DisplayName("무제한 범위는 SQL 실행 전에 거부한다")
  void rejectsUnrestrictedScope() {
    // when·then
    assertThatIllegalArgumentException()
        .isThrownBy(() -> probe.search(SPACE, new double[DIMENSIONS], TOP_K, UNRESTRICTED));
  }
}
