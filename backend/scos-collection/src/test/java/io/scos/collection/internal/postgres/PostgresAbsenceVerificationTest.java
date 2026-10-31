package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.AbsenceVerificationContract;
import io.scos.collection.internal.AbsenceVerificationScenario;
import io.scos.collection.internal.AbsenceVerificationScenario.VerificationEffects;
import io.scos.collection.internal.CommitContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 전체 부재 검증 계약")
class PostgresAbsenceVerificationTest extends AbsenceVerificationContract {
  @Override
  protected AbsenceVerificationScenario newContext() {
    PostgresSupport.truncateAll();
    return new AbsenceVerificationScenario(
        CommitContext.Ports.postgres(PostgresSupport.dataSource()),
        new PostgresBodyCompletionStore(JdbcClient.create(PostgresSupport.dataSource())));
  }

  @Test
  @DisplayName("정리의 R2 쓰기 실패는 tombstone·완료 이력·run 마감을 함께 롤백한다")
  void discoveryFailureRollsBackCleanup() {
    // given
    context.verifyAllCandidatesOutside();
    var before = context.cleanupEffects();
    withRejectedDiscoveryWrites(
        () -> {
          // then
          assertThatThrownBy(context::complete).isInstanceOf(DataIntegrityViolationException.class);
          assertThat(context.cleanupEffects()).isEqualTo(before);
        });
  }

  private void withRejectedDiscoveryWrites(Runnable assertions) {
    var jdbc = JdbcClient.create(PostgresSupport.dataSource());
    jdbc.sql(
            "ALTER TABLE reconciliation_discoveries ADD CONSTRAINT reject_absence_discovery CHECK (classification IS NULL)")
        .update();
    try {
      assertions.run();
    } finally {
      jdbc.sql("ALTER TABLE reconciliation_discoveries DROP CONSTRAINT reject_absence_discovery")
          .update();
    }
  }

  @Test
  @DisplayName("검증 결과 저장 실패는 같은 페이지의 원본·관측·확인점도 롤백한다")
  void verificationStorageFailureRollsBackPage() {
    withRejectedVerificationWrites(
        () -> {
          // then
          assertThatThrownBy(context::commitVerification)
              .isInstanceOf(DataIntegrityViolationException.class);
          assertThat(context.verificationEffects())
              .isEqualTo(new VerificationEffects(false, false, false, false));
        });
  }

  private void withRejectedVerificationWrites(Runnable assertions) {
    var jdbc = JdbcClient.create(PostgresSupport.dataSource());
    jdbc.sql(
            "ALTER TABLE collection_runs ADD CONSTRAINT reject_absence_token CHECK (absence_candidate_token IS NULL)")
        .update();
    try {
      assertions.run();
    } finally {
      jdbc.sql("ALTER TABLE collection_runs DROP CONSTRAINT reject_absence_token").update();
    }
  }
}
