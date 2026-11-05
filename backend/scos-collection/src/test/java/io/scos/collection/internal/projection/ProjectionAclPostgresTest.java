package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.OutboxPort;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("투영은 본문보다 먼저 ACL을 확정한다(접근 설계 §5.3)")
class ProjectionAclPostgresTest {
  final ProjectionAclScenario scenario = new ProjectionAclScenario();

  @AfterEach
  void closeScenario() {
    scenario.close();
  }

  @Test
  @DisplayName("지연 carrier 하나의 새 회수도 문서 ACL을 좁힌다")
  void laggingCarrierChangeStillRevokesDocument() {
    // given
    scenario.givenLaggingCarrier();
    scenario.restrictLaggingCarrier();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl())
        .extracting(DocumentAcl::status, DocumentAcl::keys)
        .containsExactly(Status.FAILED, Set.of());
  }

  @Nested
  @DisplayName("읽을 수 있는 문서가 이미 투영된 상태에서")
  class ExistingDocument {
    @BeforeEach
    void givenExistingDocument() {
      scenario.givenProjectedDocument();
    }

    @Test
    @DisplayName("상대 출처 충돌은 소유자 ACL과 유닛을 보존한다")
    void foreignSourceSkipsWithoutOverwritingOwnerAcl() {
      // given
      scenario.givenForeignSourceCollision();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl()).isEqualTo(scenario.priorAcl());
      assertThat(scenario.units()).isEqualTo(scenario.priorUnits());
    }

    @Test
    @DisplayName("상대 출처 충돌은 건너뛴 문서로 보고한다")
    void foreignCollisionReportsOwnershipSkip() {
      // given
      scenario.givenForeignSourceCollision();
      // when
      scenario.project();
      // then
      assertThat(scenario.ownershipSkipped()).containsExactly("C1/day-2025-10-14");
    }

    @Test
    @DisplayName("상대 출처 충돌은 새 출처의 운반 원본만 차단한다")
    void foreignCollisionRestrictsRawCarrier() {
      // given
      scenario.givenForeignSourceCollision();
      // when
      scenario.project();
      // then
      assertThat(scenario.foreignCarrierAcl())
          .extracting(a -> a.status(), a -> a.keys())
          .containsExactly(Status.FAILED, Set.of());
    }

    @Test
    @DisplayName("상대 출처 충돌이어도 소스 투영은 성공한다")
    void foreignCollisionCompletesProjection() {
      // given
      scenario.givenForeignSourceCollision();
      // when
      var report = scenario.project();
      // then
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("투영 점유를 잃으면 충돌 원본을 차단하거나 요청하지 않는다")
    void lostClaimCannotRestrictForeignCarrier() {
      // given
      scenario.givenForeignSourceCollision();
      scenario.loseClaimAfterSync();
      // when
      var report = scenario.project();
      // then
      assertThat(scenario.foreignCarrierAcl().status()).isEqualTo(Status.OK);
      assertThat(scenario.request()).get().extracting(OutboxPort.Request::version).isEqualTo(1L);
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUPERSEDED);
    }

    @Test
    @DisplayName("후속 요청 저장 실패는 충돌 원본 차단도 롤백한다")
    void collisionRequestFailureRollsBackRawRestriction() {
      // given
      scenario.givenForeignSourceCollision();
      scenario.rejectCollisionRequest();
      // when
      var report = scenario.project();
      // then
      assertThat(scenario.foreignCarrierAcl().status()).isEqualTo(Status.OK);
      assertThat(scenario.request()).get().extracting(OutboxPort.Request::version).isEqualTo(1L);
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.FAILED);
    }

    @Test
    @DisplayName("충돌 차단의 새 요청은 현재 투영 완료 후에도 남는다")
    void collisionLeavesFollowupRequest() {
      // given
      scenario.givenForeignSourceCollision();
      // when
      scenario.project();
      // then
      assertThat(scenario.request()).get().extracting(OutboxPort.Request::version).isEqualTo(2L);
    }

    @Test
    @DisplayName("동일 FAILED 충돌의 후속 투영은 추가 요청 없이 완료된다")
    void collisionFollowupProjectionCompletesWithoutLoop() {
      // given
      scenario.givenForeignSourceCollision();
      scenario.givenCompletedCollision();
      // when
      var report = scenario.project();
      // then
      assertThat(scenario.request()).isEmpty();
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("다음 수집의 새 OK도 다시 차단하고 소유 문서를 보존한다")
    void laterCollectionMayRetryForeignSource() {
      // given
      scenario.givenForeignSourceCollision();
      scenario.givenCompletedCollision();
      scenario.nextCollectionObservesForeignOk();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl()).isEqualTo(scenario.priorAcl());
      assertThat(scenario.units()).isEqualTo(scenario.priorUnits());
      assertThat(scenario.foreignCarrierAcl())
          .extracting(a -> a.status(), a -> a.keys())
          .containsExactly(Status.FAILED, Set.of());
    }

    @Test
    @DisplayName("ACL만 저장된 문서도 원본이 사라지면 정리한다")
    void aclOnlyOrphanIsPruned() {
      // given
      scenario.givenAclOnlyOrphan();
      scenario.deleteOrphanOriginal();
      // when
      scenario.project();
      // then
      assertThat(scenario.orphanAcls()).isEmpty();
    }

    @Test
    @DisplayName("상대 출처의 원본 삭제는 소유자 ACL을 정리하지 않는다")
    void pruneCannotDeleteForeignAcl() {
      // given
      scenario.givenForeignSourceCollision();
      scenario.deleteOriginalDocument();
      scenario.givenForeignUnitPruneCandidate();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl()).isEqualTo(scenario.priorAcl());
    }

    @Test
    @DisplayName("empty 투영은 기존 유닛을 정리한다")
    void emptyProjectionPrunesUnits() {
      // given
      scenario.projectorReturnsEmpty();
      // when
      scenario.project();
      // then
      assertThat(scenario.units()).isEmpty();
    }

    @Test
    @DisplayName("empty 투영은 기존 ACL을 정리한다")
    void emptyProjectionPrunesAcl() {
      // given
      scenario.projectorReturnsEmpty();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl()).isNull();
    }

    @Test
    @DisplayName("수집 비활성화에서도 사라진 문서의 유닛과 ACL을 정리한다")
    void disabledPruneDeletesExistingAclWithUnits() {
      // given
      scenario.disableAccessCollection();
      scenario.deleteOriginalDocument();
      // when
      scenario.project();
      // then
      assertThat(scenario.units()).isEmpty();
      assertThat(scenario.acl()).isNull();
    }

    @Test
    @DisplayName("수집 비활성화의 충돌도 실패 대신 건너뜀으로 보고한다")
    void disabledCollisionIsSkipped() {
      // given
      scenario.givenForeignSourceCollision();
      scenario.disableAccessCollection();
      // when
      var report = scenario.project();
      // then
      assertThat(scenario.ownershipSkipped()).containsExactly("C1/day-2025-10-14");
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("수집 비활성화의 충돌은 임베더 장애보다 먼저 건너뛴다")
    void disabledCollisionSkipsBeforeEmbeddingFailure() {
      // given
      scenario.givenForeignSourceCollision();
      scenario.disableAccessCollection();
      scenario.embedderFails();
      // when
      var report = scenario.project();
      // then
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
      assertThat(scenario.ownershipSkipped()).containsExactly("C1/day-2025-10-14");
    }

    @Test
    @DisplayName("수집 비활성화의 충돌은 원본 ACL과 요청을 추가하지 않는다")
    void disabledCollisionPreservesRawAcl() {
      // given
      scenario.givenForeignSourceCollision();
      scenario.disableAccessCollection();
      // when
      scenario.project();
      // then
      assertThat(scenario.foreignCarrierAcl().status()).isEqualTo(Status.OK);
      assertThat(scenario.request()).isEmpty();
    }

    @Test
    @DisplayName("임베딩이 실패해도 회수된 문서는 읽을 수 없다")
    void aclRestrictionSurvivesEmbeddingFailure() {
      // given
      scenario.revokeWithEmptyKeys();
      scenario.changeBody();
      scenario.embedderFails();
      // when
      scenario.project();
      // then
      assertThat(scenario.readableUnits()).isEmpty();
    }

    @Test
    @DisplayName("첫 임베딩을 시작할 때 모든 문서 ACL은 이미 커밋돼 있다")
    void allAclsCommitBeforeFirstEmbedding() {
      // given
      scenario.givenSecondProjectedDocument();
      scenario.restrictBothBeforeEmbedding();
      scenario.changeBody();
      scenario.embedderFails();
      // when
      scenario.project();
      // then
      var at = Instant.parse("2026-11-03T00:00:00.000001Z");
      assertThat(scenario.firstEmbeddingAcls())
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "C1/day-2025-10-14",
                      new DocumentAcl(
                          "C1/day-2025-10-14",
                          Status.FAILED,
                          Set.of(),
                          at,
                          AccessFailureReason.PROVIDER_FAILURE),
                  "C2/day-2025-10-14",
                      new DocumentAcl(
                          "C2/day-2025-10-14",
                          Status.FAILED,
                          Set.of(),
                          at,
                          AccessFailureReason.PROVIDER_FAILURE)));
    }

    @Test
    @DisplayName("원본 ACL이 같아도 새 렌더 실패 결정은 이전 OK를 닫는다")
    void renderFailureCanReplacePriorOkAcl() {
      // given
      scenario.renderFailureAtLaterClock();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .isEqualTo(
              new DocumentAcl(
                  "C1/day-2025-10-14",
                  Status.FAILED,
                  Set.of(),
                  Instant.parse("2026-11-03T00:00:01Z"),
                  AccessFailureReason.RENDER_FAILED));
    }

    @Test
    @DisplayName("같은 Clock에서도 새 회수는 단조 결정 시각으로 저장한다")
    void equalClockStillAppliesRestriction() {
      // given
      scenario.restrictAcl();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .isEqualTo(
              new DocumentAcl(
                  "C1/day-2025-10-14",
                  Status.FAILED,
                  Set.of(),
                  Instant.parse("2026-11-03T00:00:00.000001Z"),
                  AccessFailureReason.PROVIDER_FAILURE));
    }

    @Test
    @DisplayName("마이크로초 미만으로 흐른 Clock에서도 회수를 저장한다")
    void subMicrosecondClockStillAppliesRestriction() {
      // given
      scenario.advanceSubMicrosecond();
      scenario.restrictAcl();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .isEqualTo(
              new DocumentAcl(
                  "C1/day-2025-10-14",
                  Status.FAILED,
                  Set.of(),
                  Instant.parse("2026-11-03T00:00:00.000001Z"),
                  AccessFailureReason.PROVIDER_FAILURE));
    }

    @Test
    @DisplayName("지문이 같으면 문서 결정 시각도 다시 쓰지 않는다")
    void unchangedAclPreservesDecisionTime() {
      // given
      scenario.requestAgainAtLaterClock();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl()).isEqualTo(scenario.priorAcl());
    }

    @Test
    @DisplayName("렌더 중 흐른 Clock은 snapshot 결정 시각을 대체하지 않는다")
    void renderTimeDoesNotReplaceSnapshotDecisionTime() {
      // given
      scenario.restrictAcl();
      scenario.advanceClockDuringRendering();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .isEqualTo(
              new DocumentAcl(
                  "C1/day-2025-10-14",
                  Status.FAILED,
                  Set.of(),
                  Instant.parse("2026-11-03T00:00:00.000001Z"),
                  AccessFailureReason.PROVIDER_FAILURE));
    }

    @Test
    @DisplayName("ACL만 바뀌면 임베더를 호출하지 않는다")
    void aclOnlyChangeDoesNotEmbed() {
      // given
      scenario.changeAclOnly();
      // when
      scenario.project();
      // then
      assertThat(scenario.embedCalls()).isZero();
    }

    @Test
    @DisplayName("본문이 같아도 새로운 ACL 키를 저장한다")
    void appliesAclOnlyChange() {
      // given
      scenario.changeAclOnly();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .extracting(DocumentAcl::status, DocumentAcl::keys)
          .containsExactly(Status.OK, Set.of(ProjectionAclScenario.NEW_READER));
    }

    @Test
    @DisplayName("렌더 실패 문서의 ACL은 FAILED로 닫힌다")
    void projectorFailureWritesFailedAcl() {
      // given
      scenario.projectorFails();
      scenario.givenSecondDocument();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .extracting(DocumentAcl::status, DocumentAcl::keys)
          .containsExactly(Status.FAILED, Set.of());
    }

    @Test
    @DisplayName("렌더 실패 문서가 있어도 다른 정상 문서는 저장한다")
    void projectorFailureStillProjectsOtherDocuments() {
      // given
      scenario.projectorFails();
      scenario.givenSecondDocument();
      // when
      scenario.project();
      // then
      assertThat(scenario.storedDocuments()).contains(ProjectionAclScenario.OTHER_DOC);
    }

    @Test
    @DisplayName("렌더 실패 요청은 삭제하지 않고 재시도 시각을 기록한다")
    void projectorFailureKeepsRequestForRetry() {
      // given
      scenario.projectorFails();
      // when
      scenario.project();
      // then
      assertThat(scenario.request())
          .get()
          .extracting(OutboxPort.Request::attempts, OutboxPort.Request::nextAttemptAt)
          .containsExactly(1, Optional.of(Instant.parse("2026-11-03T00:00:10Z")));
    }

    @Test
    @DisplayName("렌더 실패는 project 단계의 문서 실패로 보고한다")
    void projectorFailureReportsProjectStage() {
      // given
      scenario.projectorFails();
      // when
      var report = scenario.project();
      // then
      assertThat(report.documentFailures())
          .extracting(ProjectionRun.DocumentFailure::stage)
          .containsExactly("project");
    }

    @Test
    @DisplayName("원본 변경 없이 렌더러가 회복되면 요청을 완료한다")
    void projectorRecoveryCompletesWithoutRawChange() {
      // given
      scenario.givenProjectorFailure();
      scenario.recoverProjectorAtRetry();
      // when
      scenario.project();
      // then
      assertThat(scenario.request()).isEmpty();
    }

    @Test
    @DisplayName("원본 변경 없이 회복한 렌더러는 원래 ACL을 다시 저장한다")
    void projectorRecoveryRestoresAcl() {
      // given
      scenario.givenProjectorFailure();
      scenario.recoverProjectorAtRetry();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .extracting(DocumentAcl::status, DocumentAcl::keys)
          .containsExactly(Status.OK, Set.of(ProjectionAclScenario.READER));
    }

    @Test
    @DisplayName("접근 수집 중 임베딩 실패도 요청을 재시도로 남긴다")
    void embeddingFailureKeepsRequest() {
      // given
      scenario.revokeWithEmptyKeys();
      scenario.changeBody();
      scenario.embedderFails();
      // when
      scenario.project();
      // then
      assertThat(scenario.request()).get().extracting(OutboxPort.Request::attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("pending 세대라도 먼저 ACL 회수를 적용한다")
    void pendingGenerationStillAppliesAclRestriction() {
      // given
      scenario.restrictAcl();
      scenario.pendingGeneration();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .extracting(DocumentAcl::status, DocumentAcl::keys)
          .containsExactly(Status.FAILED, Set.of());
    }

    @Test
    @DisplayName("pending 세대에서 기존 유닛은 바뀌지 않는다")
    void pendingGenerationPreservesUnits() {
      // given
      scenario.restrictAcl();
      scenario.changeBody();
      scenario.pendingGeneration();
      // when
      scenario.project();
      // then
      assertThat(scenario.units()).isEqualTo(scenario.priorUnits());
    }

    @Test
    @DisplayName("pending 세대의 투영 요청은 backoff로 남는다")
    void pendingGenerationLeavesProjectionInBackoff() {
      // given
      scenario.restrictAcl();
      scenario.pendingGeneration();
      // when
      scenario.project();
      // then
      assertThat(scenario.request())
          .get()
          .extracting(OutboxPort.Request::attempts, OutboxPort.Request::nextAttemptAt)
          .containsExactly(1, Optional.of(Instant.parse("2026-11-03T00:00:10Z")));
    }

    @Test
    @DisplayName("다른 세대라도 먼저 ACL 회수를 적용한다")
    void mismatchedGenerationStillAppliesAclRestriction() {
      // given
      scenario.restrictAcl();
      scenario.mismatchedGeneration();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .extracting(DocumentAcl::status, DocumentAcl::keys)
          .containsExactly(Status.FAILED, Set.of());
    }

    @Test
    @DisplayName("점유를 잃으면 기존 ACL을 바꾸지 못한다")
    void lostProjectionClaimCannotWriteAcl() {
      // given
      scenario.restrictAcl();
      scenario.loseClaimBeforeSync();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl()).isEqualTo(scenario.priorAcl());
    }

    @Test
    @DisplayName("그룹 묶기가 실패하면 이전 소유 문서 ACL을 닫는다")
    void groupingFailureRestrictsPreviousDocuments() {
      // given
      scenario.groupingFails();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .extracting(DocumentAcl::status, DocumentAcl::keys)
          .containsExactly(Status.FAILED, Set.of());
    }

    @Test
    @DisplayName("그룹 묶기 실패는 기존 문서 삭제로 처리하지 않는다")
    void groupingFailurePreservesUnits() {
      // given
      scenario.groupingFails();
      // when
      scenario.project();
      // then
      assertThat(scenario.units()).isEqualTo(scenario.priorUnits());
    }

    @Test
    @DisplayName("empty 문서도 세대 검사 전에 ACL을 닫는다")
    void emptyProjectionClosesAclBeforeBlockedPrune() {
      // given
      scenario.projectorReturnsEmpty();
      scenario.pendingGeneration();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .extracting(DocumentAcl::status, DocumentAcl::keys)
          .containsExactly(Status.FAILED, Set.of());
    }

    @Test
    @DisplayName("원본이 모두 사라진 문서도 세대 검사 전에 ACL을 닫는다")
    void vanishedDocumentClosesAclBeforeBlockedPrune() {
      // given
      scenario.deleteOriginalDocument();
      scenario.pendingGeneration();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl())
          .extracting(DocumentAcl::status, DocumentAcl::keys)
          .containsExactly(Status.FAILED, Set.of());
    }

    @Test
    @DisplayName("수집 비활성화에서는 기존 ACL을 갱신하지 않는다")
    void disabledProjectionDoesNotWriteAcl() {
      // given
      scenario.disableAccessCollection();
      scenario.changeAclOnly();
      // when
      scenario.project();
      // then
      assertThat(scenario.acl()).isEqualTo(scenario.priorAcl());
    }

    @Test
    @DisplayName("수집 비활성화에서도 렌더 실패는 재시도로 남는다")
    void disabledProjectorFailureKeepsRetry() {
      // given
      scenario.disableAccessCollection();
      scenario.projectorFails();
      // when
      scenario.project();
      // then
      assertThat(scenario.request()).get().extracting(OutboxPort.Request::attempts).isEqualTo(1);
    }
  }
}
