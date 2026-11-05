package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.AccessRunCompletionTest;
import io.scos.collection.internal.AccessRunScenario;
import io.scos.collection.internal.ClaimLostException;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RunStatus;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("PostgreSQL 접근 병합은 점유 상실과 실패에도 run을 마감한다")
class AccessRunCompletionPostgresTest extends AccessRunCompletionTest {
  @Override
  protected AccessRunScenario newScenario() {
    PostgresSupport.truncateAll();
    return AccessRunScenario.postgres(PostgresSupport.dataSource());
  }

  @Test
  @DisplayName("ACL 커밋 직전 점유 인계는 ACL·요청·관측을 쓰지 않는다")
  void takeoverBeforeAclCommitWritesNothing() {
    var before = scenario.acls();
    long outbox = scenario.outboxVersion();
    long changes = scenario.changes();
    // then
    assertThatThrownBy(scenario::commitAclAfterTakeover).isInstanceOf(ClaimLostException.class);
    assertThat(scenario)
        .extracting(
            AccessRunScenario::acls,
            AccessRunScenario::outboxVersion,
            AccessRunScenario::staged,
            AccessRunScenario::changes)
        .containsExactly(before, outbox, 0, changes);
  }

  @Test
  @DisplayName("병합 직전 점유 인계는 그룹을 보존하고 run을 끝낸다")
  void takeoverBeforeMergeStillFinishesRun() {
    scenario.directory("U2", "U3");
    scenario.takeoverBeforeMerge();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult, CollectionRun::errorKind)
        .containsExactly(RunStatus.FAILED, AccessResult.CLAIM_LOST, Optional.of("claim_lost"));
    assertThat(run.finishedAt()).isPresent();
    assertThat(scenario.running()).isZero();
  }

  @Test
  @DisplayName("병합 전 점유 인계는 기존 그룹 구성원을 보존한다")
  void takeoverBeforeMergePreservesGroups() {
    scenario.directory("U2", "U3");
    scenario.takeoverBeforeMerge();
    // when
    scenario.collect();
    // then
    assertThat(scenario.members()).containsExactly("U1", "U2");
  }

  @Test
  @DisplayName("병합 전 점유 인계로 끝난 run의 보관 관측을 폐기한다")
  void takeoverBeforeMergeDiscardsObservations() {
    scenario.directory("U2", "U3");
    scenario.takeoverBeforeMerge();
    // when
    scenario.collect();
    // then
    assertThat(scenario.staged()).isZero();
  }

  @Test
  @DisplayName("병합 SQL 장애는 신원 쓰기를 롤백하고 본문 성공을 유지한다")
  void mergeFailureDoesNotRollbackBodyFinish() {
    scenario.directory("U2", "U3");
    scenario.rejectGroups();
    CollectionRun run;
    try {
      run = scenario.collect();
    } finally {
      scenario.allowGroups();
    }
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.SUCCEEDED, AccessResult.INCOMPLETE);
    assertThat(scenario)
        .extracting(
            s -> s.hasIdentity("U3"), AccessRunScenario::members, AccessRunScenario::running)
        .containsExactly(false, List.of("U1", "U2"), 0);
  }

  @Test
  @DisplayName("전체 신원 누락은 회수 후 접근 미완료로 마감한다")
  void completenessViolationFinishesIncompleteAfterNarrowing() {
    scenario.missingIdentity();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.SUCCEEDED, AccessResult.INCOMPLETE);
    assertThat(scenario.members()).containsExactly("U2");
    assertThat(scenario.staged()).isZero();
  }

  @Test
  @DisplayName("COMPLETE 롤백 뒤 점유 인계는 재병합 쓰기를 막는다")
  void completenessFallbackClaimLostWritesNothing() {
    scenario.missingIdentity();
    scenario.takeoverBeforeFallback();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.CLAIM_LOST);
    assertThat(scenario)
        .extracting(
            AccessRunScenario::members,
            s -> s.hasIdentity("U3"),
            AccessRunScenario::running,
            AccessRunScenario::currentOwner)
        .containsExactly(List.of("U1", "U2"), false, 0, "successor");
  }

  @Test
  @DisplayName("성공 스냅샷의 점유 인계는 정리 없이 부분 성공으로 끝낸다")
  void takeoverDuringSuccessfulSnapshotSkipsReconcile() {
    scenario.snapshot();
    scenario.directory("U2");
    scenario.takeoverBeforeMerge();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::deleted, CollectionRun::errorKind)
        .containsExactly(RunStatus.PARTIALLY_SUCCEEDED, 0, Optional.of("claim_lost"));
    assertThat(scenario.activeItems()).isEqualTo(2);
    assertThat(scenario.running()).isZero();
  }

  @Test
  @DisplayName("상충 관측도 ACL 회수와 후속 실패를 보존하고 미완료로 끝낸다")
  void conflictingObservationFinishesIncompleteAfterRestriction() {
    scenario.conflictingRestriction();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.SUCCEEDED, AccessResult.INCOMPLETE);
    assertThat(scenario.acls())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "file:F1", RawAcl.observed(Status.OK, Set.of(), AccessRunScenario.T1),
                "file:F2", RawAcl.observed(Status.FAILED, Set.of(), AccessRunScenario.T1)));
    assertThat(scenario.outboxVersion()).isEqualTo(2);
  }

  @Test
  @DisplayName("run 마감 트랜잭션의 마지막 저장은 변경 로그다")
  void changeAppendRemainsFinalWrite() {
    scenario.directory("U2");
    scenario.bodyPartial();
    scenario.traceWrites();
    try {
      scenario.collect();
      // then
      assertThat(scenario.lastCompletionWrite()).isEqualTo("changes.appendAll");
    } finally {
      scenario.stopTracingWrites();
    }
  }
}
