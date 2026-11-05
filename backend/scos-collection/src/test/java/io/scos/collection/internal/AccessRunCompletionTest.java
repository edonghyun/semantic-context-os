package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.RawAcl;
import io.scos.support.LogCapture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

@DisplayName("본문과 독립된 접근 결과로 run을 마감한다")
@Isolated
public class AccessRunCompletionTest {
  protected AccessRunScenario scenario;

  protected AccessRunScenario newScenario() {
    return AccessRunScenario.memory();
  }

  @BeforeEach
  protected void prepare() {
    scenario = newScenario();
  }

  @AfterEach
  protected void closeScenario() {
    scenario.close();
  }

  @Test
  @DisplayName("거부 항목이 있는 정상 본문은 거부 포함 성공으로 마감한다")
  protected void bodyRejectionIsReflectedInTerminalStatus() {
    scenario.bodyPageWithRejection();
    var done = scenario.collect();
    // then
    assertThat(done.status()).isEqualTo(RunStatus.SUCCEEDED_WITH_REJECTIONS);
  }

  @Test
  @DisplayName("마감한 수집의 점유는 후속 작업자가 즉시 얻는다")
  protected void terminalRunReleasesClaim() {
    scenario.collect();
    // when
    var next = scenario.claimBySuccessor();
    // then
    assertThat(next).isPresent();
  }

  @Test
  @DisplayName("누락 대상 닫기 중 취소되어도 완료한 디렉터리는 전체 병합한다")
  protected void missingTargetCancellationKeepsDirectoryCompleteness() {
    scenario.directory("U1", "U2", "U3");
    scenario.missingTargets(501);
    scenario.cancelAfterFirstMissingBatch();
    // when
    scenario.collect();
    // then
    assertThat(scenario.members()).containsExactly("U1", "U2", "U3");
  }

  @Test
  @DisplayName("설정 검증 실패는 수집을 시작하기 전에 전파된다")
  protected void configurationIsValidatedBeforeCollecting() {
    scenario.rejectConfiguration();
    // when·then
    assertThatThrownBy(scenario::collect).isInstanceOf(CallFailureException.class);
  }

  @Test
  @DisplayName("없는 소스의 수집은 원천 부재 예외로 거부한다")
  protected void missingSourceIsRejected() {
    // when·then
    assertThatThrownBy(scenario::collectMissingSource).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("어댑터 없는 소스의 수집은 설정 예외로 거부한다")
  protected void missingAdapterIsRejected() {
    scenario.sourceWithoutAdapter();
    // when·then
    assertThatThrownBy(scenario::collect).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("본문 반환 전 heartbeat 저장 실패는 본문 성공으로 남기지 않는다")
  protected void unhealthyHeartbeatDoesNotFinishBodyAsSuccess() {
    scenario.heartbeatFailsAfterBody();
    var done = scenario.collect();
    // then
    assertThat(done.status()).isEqualTo(RunStatus.FAILED);
  }

  @Test
  @DisplayName("이미 heartbeat가 실패한 페이지는 본문을 저장하지 않는다")
  protected void unhealthyHeartbeatPreventsBodyPageWrite() {
    scenario.bodyPageAfterFailedHeartbeat();
    scenario.collect();
    // then
    assertThat(scenario.activeItems()).isEqualTo(1);
  }

  @Test
  @DisplayName("미지원 접근은 heartbeat 저장 장애에도 UNSUPPORTED를 보존한다")
  protected void unsupportedResultSurvivesHeartbeatStorageFailure() {
    scenario.unsupportedWithFailedHeartbeat();
    // when
    var done = scenario.collect();
    // then
    assertThat(done.accessResult()).isEqualTo(AccessResult.UNSUPPORTED);
  }

  @Test
  @DisplayName("수집 마감은 heartbeat 예약을 해제한다")
  protected void terminalRunStopsHeartbeatSchedule() {
    // when
    scenario.collect();
    // then
    assertThat(scenario.liveHeartbeats()).isZero();
  }

  @Test
  @DisplayName("누락 첫 배치 뒤 취소하면 선정 순서의 마지막 대상은 건드리지 않는다")
  protected void cancelledMissingBatchPreservesSelectionTail() {
    scenario.missingTargets(501);
    scenario.cancelAfterFirstMissingBatch();
    // when
    scenario.collect();
    // then
    assertThat(scenario.acls().get("file:F1").status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("대상 누락은 정상 완료한 디렉터리의 새 구성원을 막지 않는다")
  protected void missingTargetDoesNotLimitCompleteDirectory() {
    scenario.directory("U1", "U2", "U3");
    scenario.missingTargetAcl();
    // when
    scenario.collect();
    // then
    assertThat(scenario.members()).containsExactly("U1", "U2", "U3");
  }

  @Test
  @DisplayName("본문 실패에도 전체 접근 회수가 다음 신원 조회에 반영된다")
  protected void bodyFailureStillCollectsAccess() {
    scenario.bodyFails();
    scenario.directory("U2");
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.FAILED, AccessResult.COMPLETE);
    assertThat(scenario.keys()).doesNotContain("group:access-source:C1");
  }

  @Test
  @DisplayName("본문 부분 성공도 전체 디렉터리의 새 구성원을 제한하지 않는다")
  protected void bodyPartialDoesNotLimitCompleteDirectory() {
    scenario.bodyPartial();
    scenario.directory("U1", "U2", "U3");
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.PARTIALLY_SUCCEEDED, AccessResult.COMPLETE);
    assertThat(scenario.members()).containsExactly("U1", "U2", "U3");
  }

  @Test
  @DisplayName("취소된 run은 접근을 호출하지 않고 미완료로 마감한다")
  protected void cancelledRunDoesNotCallAccess() {
    scenario.cancelled();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.CANCELLED, AccessResult.INCOMPLETE);
    assertThat(scenario.accessCalls()).isZero();
  }

  @Test
  @DisplayName("본문 정상 반환 뒤 취소 플래그는 본문 성공을 변경하지 않는다")
  protected void successfulBodyRemainsSucceededAfterCancellation() {
    // given
    scenario.cancelAfterSuccessfulBody();
    // when
    CollectionRun done = scenario.collect();
    // then
    assertThat(done)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.SUCCEEDED, AccessResult.INCOMPLETE);
  }

  @Test
  @DisplayName("접근 미완료가 본문 성공을 실패로 바꾸지 않는다")
  protected void incompleteAccessDoesNotMakeBodyFail() {
    scenario.incompleteAccess();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.SUCCEEDED, AccessResult.INCOMPLETE);
  }

  @Test
  @DisplayName("접근 완료 시각은 접근 시도 종료 시각이다")
  protected void recordsAccessAttemptEnd() {
    CollectionRun run = scenario.collect();
    // then
    assertThat(run.accessFinishedAt()).contains(AccessRunScenario.T1.plusSeconds(1));
  }

  @Test
  @DisplayName("접근 비활성은 호출과 완료 시각을 남기지 않는다")
  protected void disabledAccessHasNoFinishedAt() {
    scenario.disabled();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::accessResult, CollectionRun::accessFinishedAt)
        .containsExactly(AccessResult.SKIPPED, Optional.empty());
    assertThat(scenario.accessCalls()).isZero();
  }

  @Test
  @DisplayName("본문 성공 뒤 점유 인계는 실패 마감으로 전환한다")
  protected void lostAccessIsTerminal() {
    scenario.takeoverAfterBody();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult, CollectionRun::errorKind)
        .containsExactly(RunStatus.FAILED, AccessResult.CLAIM_LOST, Optional.of("claim_lost"));
  }

  @Test
  @DisplayName("전체성 재병합의 실제 미완료 모드를 저장한다")
  protected void missingIdentityUsesActualMode() {
    scenario.missingIdentity();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.members()).containsExactly("U2");
  }

  @Test
  @DisplayName("상충이 강제한 미완료 모드를 저장한다")
  protected void conflictUsesActualMode() {
    scenario.conflictingRestriction();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
  }

  @Test
  @DisplayName("그룹 참조 위반 run은 회수를 반영하고 INCOMPLETE로 마감한다")
  protected void invalidGroupReferenceStillFinishesWithRevocation() {
    // given
    scenario.invalidGroupReference();
    // when
    CollectionRun done = scenario.collect();
    // then
    assertThat(done.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.members()).containsExactly("U2");
  }

  @Test
  @DisplayName("그룹 참조 위반으로 제한한 병합은 소스와 run을 경고로 남긴다")
  protected void restrictedMergeLogsWarning() {
    // given
    scenario.invalidGroupReference();
    try (var log = LogCapture.of(AccessCompletion.class)) {
      // when
      scenario.collect();
      // then
      assertThat(log.events("collection.access.restricted"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(
                          List.of(
                              event.path("level").asString(),
                              event.path("sourceInstanceId").asString(),
                              event.path("runId").asString()))
                      .containsExactly("warn", "access-source", "access-run"));
    }
  }

  @Test
  @DisplayName("병합 경계의 장애는 접근 미완료로 격리한다")
  protected void mergeErrorIsIncomplete() {
    scenario.directory("U1", "U2", "U3");
    scenario.mergeFails();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.SUCCEEDED, AccessResult.INCOMPLETE);
    assertThat(scenario.members()).containsExactly("U1", "U2");
  }

  @Test
  @DisplayName("미완료 디렉터리는 관측한 회수만 반영한다")
  protected void incompleteDirectoryOnlyNarrows() {
    scenario.directory("U2", "U3");
    scenario.incompleteAccess();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.members()).containsExactly("U2");
  }

  @Test
  @DisplayName("접근 수집 뒤 설정 변경은 그룹 확장과 본문 정리를 막는다")
  protected void sourceChangePreventsDirectoryExpansion() {
    scenario.directory("U1", "U2", "U3");
    scenario.configurationChangesAfterAccess();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult, CollectionRun::errorKind)
        .containsExactly(
            RunStatus.FAILED, AccessResult.INCOMPLETE, Optional.of("source_configuration_changed"));
    assertThat(scenario.members()).containsExactly("U1", "U2");
  }

  @Test
  @DisplayName("접근 비활성 성공도 최종 저장에서 실제 점유를 검사한다")
  protected void disabledSuccessStillFencesFinalization() {
    scenario.disabled();
    scenario.takeoverAfterBody();
    CollectionRun run = scenario.collect();
    // then
    assertThat(run)
        .extracting(CollectionRun::status, CollectionRun::accessResult, CollectionRun::errorKind)
        .containsExactly(RunStatus.FAILED, AccessResult.SKIPPED, Optional.of("claim_lost"));
  }

  @Test
  @DisplayName("병합 직전 점유 상실은 메모리에서도 그룹 확장을 막는다")
  protected void claimLostBeforeMergeCannotExpandDirectory() {
    scenario.directory("U2", "U3");
    scenario.takeoverBeforeMerge();
    CollectionRun done = scenario.collect();
    // then
    assertThat(done)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.FAILED, AccessResult.CLAIM_LOST);
    assertThat(scenario.members()).containsExactly("U1", "U2");
  }

  @Test
  @DisplayName("병합 실패 경고는 소스·run·원인 예외를 보존한다")
  protected void mergeFailureLogsCause() {
    scenario.mergeFails();
    try (var log = LogCapture.of(AccessCompletion.class)) {
      scenario.collect();
      // then
      assertThat(log.events("collection.access.incomplete"))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(
                        List.of(
                            event.path("level").asString(),
                            event.path("sourceInstanceId").asString(),
                            event.path("runId").asString(),
                            event.path("error").path("type").asString(),
                            event.path("error").path("message").asString()))
                    .containsExactly(
                        "warn",
                        "access-source",
                        "access-run",
                        "java.lang.IllegalStateException",
                        "merge unavailable");
              });
    }
  }

  @Test
  @DisplayName("접근 확정 뒤에는 해당 run의 관측을 정리한다")
  protected void completionDiscardsAccessObservations() {
    scenario.directory("U2");
    scenario.collect();
    // then
    assertThat(scenario.staged()).isZero();
  }

  @Test
  @DisplayName("본문 실패도 병합 guard의 점유 상실 결과를 보존한다")
  protected void bodyFailureDoesNotHideMergeClaimLoss() {
    scenario.bodyFails();
    scenario.directory("U2", "U3");
    scenario.takeoverBeforeMerge();
    CollectionRun done = scenario.collect();
    // then
    assertThat(done)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.FAILED, AccessResult.CLAIM_LOST);
    assertThat(scenario.members()).containsExactly("U1", "U2");
  }

  @Test
  @DisplayName("접근 수집 반환 직전 갱신 실패는 미병합 COMPLETE를 기록하지 않는다")
  protected void failedHeartbeatDoesNotRecordUnmergedCompleteAccess() {
    scenario.directory("U1", "U2", "U3");
    scenario.heartbeatFailsAtAccessReturn();
    CollectionRun done = scenario.collect();
    // then
    assertThat(done.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.members()).containsExactly("U1", "U2");
  }

  @ParameterizedTest
  @CsvSource({"false, FAILED", "true, PARTIALLY_SUCCEEDED"})
  @DisplayName("본문 실패 뒤 접근 반환 직전 점유 상실은 CLAIM_LOST로 기록한다")
  protected void failedBodyStillRecordsClaimLostHeartbeat(boolean partial, RunStatus expected) {
    if (partial) scenario.bodyPartial();
    else scenario.bodyFails();
    scenario.directory("U1", "U2", "U3");
    scenario.claimLostAtAccessReturn();
    CollectionRun done = scenario.collect();
    // then
    assertThat(done)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(expected, AccessResult.CLAIM_LOST);
    assertThat(scenario.members()).containsExactly("U1", "U2");
  }

  @Test
  @DisplayName("접근 비활성은 heartbeat 점유 상실에도 SKIPPED를 유지한다")
  protected void disabledAccessStillSkipsAfterHeartbeatClaimLost() {
    scenario.disabled();
    scenario.claimLostAtBodyReturn();
    CollectionRun done = scenario.collect();
    // then
    assertThat(done.accessResult()).isEqualTo(AccessResult.SKIPPED);
  }

  @Test
  @DisplayName("heartbeat 저장 오류에도 관측한 회수는 좁히기로 반영한다")
  protected void heartbeatFailureStillNarrowsObservedRevocation() {
    scenario.directory("U2", "U3");
    scenario.heartbeatFailsAtAccessReturn();
    CollectionRun done = scenario.collect();
    // then
    assertThat(done.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.members()).containsExactly("U2");
  }

  @Test
  @DisplayName("본문 성공 뒤 점유 상실 마감은 실패 이벤트를 남긴다")
  protected void terminalClaimLossLogsFailure() {
    scenario.takeoverAfterBody();
    try (var log = LogCapture.of(CollectionRuntime.class)) {
      scenario.collect();
      // then
      assertThat(log.events("collection.run.failed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(
                          List.of(
                              event.path("detail").path("status").asString(),
                              event.path("detail").path("errorKind").asString()))
                      .containsExactly("failed", "claim_lost"));
    }
  }

  @Test
  @DisplayName("접근 병합 뒤 점유 상실 마감도 커밋한 접근 결과를 유지한다")
  protected void terminalClaimLossKeepsCommittedAccess() {
    scenario.directory("U2");
    scenario.takeoverAfterMerge();
    CollectionRun done = scenario.collect();
    // then
    assertThat(done)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.FAILED, AccessResult.COMPLETE);
    assertThat(scenario.members()).containsExactly("U2");
  }

  @Test
  @DisplayName("성공 마감의 저장 오류는 커밋한 접근 결과를 보존하고 실패로 마감한다")
  protected void completionStorageFailureStillFinishesRun() {
    // given
    scenario.directory("U2");
    scenario.completionStorageFails(1);
    // when
    CollectionRun done = scenario.collect();
    // then
    assertThat(done)
        .extracting(CollectionRun::status, CollectionRun::accessResult, CollectionRun::errorKind)
        .containsExactly(RunStatus.FAILED, AccessResult.COMPLETE, Optional.of("storage_failed"));
    assertThat(done.finishedAt()).isPresent();
  }

  @Test
  @DisplayName("성공 마감의 저장 오류도 run 종료 메트릭을 한 번 기록한다")
  protected void completionStorageFailureRecordsMetric() {
    // given
    scenario.completionStorageFails(1);
    // when
    scenario.collect();
    // then
    assertThat(scenario.failedRunCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("성공 마감의 저장 오류는 실패 이벤트에 저장 오류 분류를 남긴다")
  protected void completionStorageFailureLogsError() {
    // given
    scenario.completionStorageFails(1);
    try (var log = LogCapture.of(CollectionRuntime.class)) {
      // when
      scenario.collect();
      // then
      assertThat(log.events("collection.run.failed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(
                          List.of(
                              event.path("detail").path("status").asString(),
                              event.path("detail").path("errorKind").asString()))
                      .containsExactly("failed", "storage_failed"));
    }
  }

  @Test
  @DisplayName("실패 마감도 실패하면 최초 오류에 두 번째 오류를 suppressed로 보존한다")
  protected void completionStorageFailurePreservesBothErrors() {
    // given
    scenario.completionStorageFails(2);
    // when·then
    assertThatThrownBy(scenario::collect)
        .isInstanceOf(CannotGetJdbcConnectionException.class)
        .hasMessage("completion storage unavailable")
        .satisfies(
            error ->
                assertThat(error.getSuppressed())
                    .extracting(Throwable::getMessage)
                    .containsExactly("completion storage unavailable"));
  }

  @Test
  @DisplayName("본문 페이지를 커밋한 뒤 마감이 실패하면 main처럼 부분 성공으로 남긴다")
  protected void completionStorageFailureAfterPageKeepsPartialBody() {
    // given
    scenario.bodySucceedsWithPage();
    scenario.completionStorageFails(1);
    // when
    CollectionRun done = scenario.collect();
    // then
    assertThat(done)
        .extracting(CollectionRun::status, CollectionRun::deleted)
        .containsExactly(RunStatus.PARTIALLY_SUCCEEDED, 0);
    assertThat(scenario.activeItems()).isEqualTo(2);
  }

  @Test
  @DisplayName("접근 도중 heartbeat 저장 오류도 관측한 회수를 반영한다")
  protected void interruptedHeartbeatStillNarrowsObservedRevocation() {
    scenario.directory("U2", "U3");
    scenario.heartbeatFailsDuringAccess();
    CollectionRun done = scenario.collect();
    // then
    assertThat(done.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.members()).containsExactly("U2");
  }

  @Test
  @DisplayName("미지원 접근 결과는 본문 마감의 설정 변경에도 유지한다")
  protected void terminalConfigurationChangeKeepsUnsupportedAccess() {
    scenario.unsupportedWithChangedConfiguration();
    CollectionRun done = scenario.collect();
    // then
    assertThat(done)
        .extracting(CollectionRun::status, CollectionRun::accessResult, CollectionRun::errorKind)
        .containsExactly(
            RunStatus.FAILED,
            AccessResult.UNSUPPORTED,
            Optional.of("source_configuration_changed"));
  }

  @Test
  @DisplayName("정상 반환해도 대상 ACL이 빠지면 접근 run은 INCOMPLETE다")
  protected void missingTargetObservationIsIncomplete() {
    // given
    scenario.missingTargetAcl();
    // when
    CollectionRun done = scenario.collect();
    // then
    assertThat(done)
        .extracting(CollectionRun::status, CollectionRun::accessResult)
        .containsExactly(RunStatus.SUCCEEDED, AccessResult.INCOMPLETE);
  }

  @Test
  @DisplayName("누락된 대상의 이전 OK 키는 FAILED 빈 키로 닫는다")
  protected void missingTargetObservationClearsOldKeys() {
    // given
    scenario.missingTargetAcl();
    // when
    scenario.collect();
    // then
    assertThat(scenario.acls())
        .containsEntry(
            "file:F1",
            new RawAcl.Observed(
                Status.FAILED,
                Set.of(),
                AccessRunScenario.T1.plusSeconds(1),
                AccessFailureReason.MISSING_TARGET));
  }

  @Test
  @DisplayName("누락 대상은 저장된 관측 시각이 미래여도 고수위를 보존하며 닫는다")
  protected void missingTargetAtFutureFenceFailsClosed() {
    // given
    scenario.futureTargetAcl();
    scenario.missingTargetAcl();
    // when
    scenario.collect();
    // then
    assertThat(scenario.acls())
        .containsEntry(
            "file:F1",
            new RawAcl.Observed(
                Status.FAILED,
                Set.of(),
                AccessRunScenario.T1.plusSeconds(10),
                AccessFailureReason.MISSING_TARGET));
  }

  @Test
  @DisplayName("첫 누락 배치 뒤 취소하면 뒤의 대상 ACL은 저장하지 않는다")
  protected void cancellationAfterMissingBatchStopsFurtherWrites() {
    // given
    scenario.missingTargets(501);
    scenario.cancelAfterFirstMissingBatch();
    // when
    scenario.collect();
    // then
    assertThat(scenario.failedTargetCount()).isEqualTo(500);
  }

  @Test
  @DisplayName("NotObserved는 이번 접근 관측을 대신하지 못한다")
  protected void unobservedTargetIsFailed() {
    // given
    scenario.unobservedTargetAcl();
    // when
    scenario.collect();
    // then
    assertThat(scenario.acls().get("file:F1").status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("누락 대상만 닫고 이미 관측한 다른 대상 ACL은 보존한다")
  protected void missingTargetPreservesObservedAcl() {
    // given
    scenario.partlyObservedTargets();
    // when
    scenario.collect();
    // then
    assertThat(scenario.acls())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "file:F1",
                RawAcl.observed(
                    Status.OK,
                    Set.of(AccessKey.parse("user:access-source:U1")),
                    AccessRunScenario.T1),
                "file:F2",
                new RawAcl.Observed(
                    Status.FAILED,
                    Set.of(),
                    AccessRunScenario.T1.plusSeconds(1),
                    AccessFailureReason.MISSING_TARGET)));
  }

  @Test
  @DisplayName("대상 ACL 누락은 성공한 본문을 삭제하지 않는다")
  protected void missingTargetPreservesSuccessfulBody() {
    // given
    scenario.missingTargetAcl();
    // when
    scenario.collect();
    // then
    assertThat(scenario.activeItems()).isEqualTo(1);
  }

  @Test
  @DisplayName("대상 ACL 누락의 FAILED 저장은 투영을 요청한다")
  protected void missingTargetRequestsProjection() {
    scenario.missingTargetAcl();
    // when
    scenario.collect();
    // then
    assertThat(scenario.outboxVersion()).isEqualTo(1);
  }
}
