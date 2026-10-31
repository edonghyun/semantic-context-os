package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("키워드 단계 프로브는 실제 단계 SQL이 실행되어야 관측을 기록한다")
class KeywordStageProbeTest {
  private static final SpaceId SPACE = new SpaceId("keyword-stage-probe");
  private static final int TOP_K = 1;
  private static final SearchFilter UNRESTRICTED =
      new SearchFilter(true, null, AccessScope.singleUser());
  private final KeywordStageProbe probe = new KeywordStageProbe(PostgresSupport.dataSource());

  @BeforeEach
  void clearCorpus() {
    PostgresSupport.truncateAll();
  }

  @Test
  @DisplayName("단계 SQL이 실행되지 않으면 관측을 거부한다")
  void rejectsUnobservedStages() {
    // when·then
    assertThatIllegalStateException()
        .isThrownBy(() -> probe.observe(SPACE, " ", TOP_K, UNRESTRICTED))
        .withMessageContaining("단계 SQL");
  }

  @Test
  @DisplayName("결과가 비어도 실행된 ALL 단계는 기록한다")
  void recordsExecutedStageWithoutHits() {
    // when
    var observation = probe.observe(SPACE, "정", TOP_K, UNRESTRICTED);
    // then
    assertThat(observation.reached()).containsExactly("ALL");
  }
}
