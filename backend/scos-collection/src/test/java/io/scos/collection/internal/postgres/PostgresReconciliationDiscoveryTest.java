package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.CheckpointConflictException;
import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.ReconciliationDiscoveryContract;
import io.scos.collection.internal.ReconciliationDiscoveryScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL R2 발견 집계 계약")
class PostgresReconciliationDiscoveryTest extends ReconciliationDiscoveryContract {
  @Override
  protected ReconciliationDiscoveryScenario newContext() {
    PostgresSupport.truncateAll();
    return new ReconciliationDiscoveryScenario(
        CommitContext.Ports.postgres(PostgresSupport.dataSource()),
        new PostgresBodyCompletionStore(JdbcClient.create(PostgresSupport.dataSource())));
  }

  @Test
  @DisplayName("체크포인트 충돌은 같은 커밋의 발견·분모·비교 기준도 롤백한다")
  void checkpointConflictRollsBackDiscovery() {
    // given
    context.checkpointWillConflict();
    var before = context.effects();
    // when·then
    assertThatThrownBy(context::reconcile).isInstanceOf(CheckpointConflictException.class);
    assertThat(context.effects()).isEqualTo(before);
  }

  @Test
  @DisplayName("R2 기록 실패는 같은 커밋의 원본·본문 완료·관측·확인점을 롤백한다")
  void discoveryFailureRollsBackEntirePage() {
    var before = context.effects();
    withRejectedDiscoveryWrites(
        () -> {
          // then
          assertThatThrownBy(context::reconcile)
              .isInstanceOf(DataIntegrityViolationException.class);
          assertThat(context.effects()).isEqualTo(before);
        });
  }

  private void withRejectedDiscoveryWrites(Runnable assertions) {
    var jdbc = JdbcClient.create(PostgresSupport.dataSource());
    jdbc.sql(
            "ALTER TABLE reconciliation_discoveries ADD CONSTRAINT reject_eligible_reconciliation CHECK (NOT eligible)")
        .update();
    try {
      assertions.run();
    } finally {
      jdbc.sql(
              "ALTER TABLE reconciliation_discoveries DROP CONSTRAINT reject_eligible_reconciliation")
          .update();
    }
  }
}
