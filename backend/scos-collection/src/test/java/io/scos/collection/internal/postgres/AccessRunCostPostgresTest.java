package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.AccessRunCostContract;
import io.scos.collection.internal.AccessRunCostQueryScenario;
import io.scos.collection.internal.AccessRunScenario;
import io.scos.spi.connector.AccessCost;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("db")
@DisplayName("PostgreSQL 접근 비용은 run별 저장과 조회 계약을 지킨다(T-c10a/b)")
class AccessRunCostPostgresTest extends AccessRunCostContract {
  @Override
  protected AccessRunCostQueryScenario newQueryScenario() {
    return AccessRunCostQueryScenario.postgres(PostgresSupport.dataSource());
  }

  @ParameterizedTest
  @ValueSource(strings = {"33", "34"})
  @DisplayName("이전 버전 run은 V35 뒤에도 비용이 미측정이다")
  void preMigrationRunCostRemainsUnavailable(String version) {
    var rehearsal = MigrationRehearsal.freshDatabase("cost_upgrade").migrateTo(version);
    rehearsal
        .jdbc()
        .sql(
            "INSERT INTO collection_runs (run_id, source_instance_id, status, started_at) VALUES ('old-run', 'drive-a', 'succeeded', '2026-10-14T00:00:00Z')")
        .update();
    // when
    rehearsal.migrateToLatest();
    // then
    assertThat(
            rehearsal
                .jdbc()
                .sql(
                    "SELECT access_target_count IS NULL AND access_call_count IS NULL AND access_elapsed_ms IS NULL AND access_cost_recorded_at IS NULL FROM collection_runs WHERE run_id = 'old-run'")
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "access_target_count = -1, access_elapsed_ms = 0, access_cost_recorded_at = now()",
        "access_target_count = 0, access_call_count = -1, access_elapsed_ms = 0, access_cost_recorded_at = now()",
        "access_target_count = 0, access_elapsed_ms = -1, access_cost_recorded_at = now()",
        "access_target_count = 0",
        "access_elapsed_ms = 0",
        "access_cost_recorded_at = now()",
        "access_call_count = 0",
        "access_target_count = 0, access_elapsed_ms = 0",
        "access_target_count = 0, access_cost_recorded_at = now()",
        "access_elapsed_ms = 0, access_cost_recorded_at = now()"
      })
  @DisplayName("음수나 부분 비용 행은 DB 제약으로 거부한다")
  void malformedCostRowIsRejected(String assignments) {
    scenario.startCostRun();
    // when·then
    assertThatThrownBy(() -> scenario.invalidStoredCost(assignments))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("access_");
  }

  @Test
  @DisplayName("병합과 비용과 본문 마감은 세 트랜잭션으로 순서대로 확정한다")
  void costCommitsBetweenMergeAndTerminal() {
    scenario.directory("U2", "U3");
    scenario.traceCostWrites();
    try {
      // when
      scenario.collect();
      // then
      assertThat(scenario.costWriteOrder()).containsExactly("merge", "cost", "terminal");
      assertThat(scenario.costWriteTransactions()).isEqualTo(3);
    } finally {
      scenario.stopTracingCostWrites();
    }
  }

  @Test
  @DisplayName("호출자 트랜잭션의 실패는 비용 저장도 롤백한다")
  void failedTransactionRollsBackCost() {
    scenario.startCostRun();
    // when·then
    assertThatThrownBy(() -> scenario.rollbackCost(new AccessCost(2, OptionalLong.of(4), 1250)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected cost transaction failure");
    assertThat(scenario.costRun().accessCost()).isEmpty();
  }

  @Override
  protected AccessRunScenario newScenario() {
    PostgresSupport.truncateAll();
    return AccessRunScenario.postgres(PostgresSupport.dataSource());
  }
}
