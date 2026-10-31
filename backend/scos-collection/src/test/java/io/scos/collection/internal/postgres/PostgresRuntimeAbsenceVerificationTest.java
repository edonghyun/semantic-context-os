package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.BodyCommitScenario;
import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.RuntimeAbsenceVerificationContract;
import io.scos.collection.internal.RuntimeAbsenceVerificationScenario;
import io.scos.engine.support.time.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 런타임 부재 검증 계약")
class PostgresRuntimeAbsenceVerificationTest extends RuntimeAbsenceVerificationContract {
  @Override
  protected RuntimeAbsenceVerificationScenario newContext() {
    PostgresSupport.truncateAll();
    var ds = PostgresSupport.dataSource();
    return new RuntimeAbsenceVerificationScenario(
        CommitContext.Ports.postgres(ds),
        adapter ->
            PostgresCollectionRuntime.over(ds, new MutableClock(BodyCommitScenario.NOW))
                .adapter(adapter)
                .build());
  }

  @Test
  @DisplayName("저장된 검증 요구가 유실돼도 시작 시 캡처한 요구로 전체 정리를 보류한다")
  void lostRequirementDoesNotFallBackToLegacyCleanup() {
    var context = newContext();
    context.verifyOutsideCandidate();
    context.beforeReturn(() -> clearColumn("reconciliation_fingerprint"));
    var report = context.collect();
    // then
    assertThat(report.errorKind()).contains(RunCompletion.ABSENCE_DEFERRED);
    assertThat(report.deleted()).isZero();
  }

  @Test
  @DisplayName("저장됐던 검증 결과가 유실되면 전체 정리와 성공 이력을 보류한다")
  void lostResultDoesNotCleanUp() {
    var context = newContext();
    context.verifyOutsideCandidate();
    context.beforeReturn(() -> clearColumn("absence_candidate_token"));
    var report = context.collect();
    // then
    assertThat(report.errorKind()).contains(RunCompletion.ABSENCE_DEFERRED);
    assertThat(report.deleted()).isZero();
    assertThat(context.lastSnapshotCompletion()).isEmpty();
  }

  private void clearColumn(String column) {
    JdbcClient.create(PostgresSupport.dataSource())
        .sql("UPDATE collection_runs SET " + column + " = NULL WHERE source_instance_id = :source")
        .param("source", BodyCommitScenario.SOURCE.value())
        .update();
  }

  @Test
  @DisplayName("검증 요구 저장 실패도 시작한 run을 failed로 마감한다")
  void requirementStorageFailureFinishesStartedRun() {
    var context = newContext();
    withRejectedRequirementWrites(
        () -> {
          // when·then
          assertThatThrownBy(context::collect).isInstanceOf(DataIntegrityViolationException.class);
          assertThat(context.latestStatus()).isEqualTo(RunStatus.FAILED);
          assertThat(context.claimIsAvailable()).isTrue();
        });
  }

  private void withRejectedRequirementWrites(Runnable assertions) {
    var jdbc = JdbcClient.create(PostgresSupport.dataSource());
    jdbc.sql(
            "ALTER TABLE collection_runs ADD CONSTRAINT reject_absence_requirement CHECK (reconciliation_fingerprint IS NULL)")
        .update();
    try {
      assertions.run();
    } finally {
      jdbc.sql("ALTER TABLE collection_runs DROP CONSTRAINT reject_absence_requirement").update();
    }
  }
}
