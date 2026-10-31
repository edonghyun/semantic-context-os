package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CollectionState.AbsenceStatus;
import io.scos.spi.connector.SnapshotScope;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("전체 정리는 현재 후보와 현재 run의 부재 증거를 대조한다")
public abstract class AbsenceVerificationContract {
  protected AbsenceVerificationScenario context;

  protected abstract AbsenceVerificationScenario newContext();

  @BeforeEach
  void prepare() {
    context = newContext();
  }

  @ParameterizedTest
  @EnumSource(AbsenceEvidenceScenario.Change.class)
  @DisplayName("부재 검증 token은 항목·소유자의 원본·완료·진행 증거가 바뀌면 무효가 된다")
  void changedEvidenceInvalidatesToken(AbsenceEvidenceScenario.Change change) {
    var evidence = new AbsenceEvidenceScenario(context, change);
    var previous = evidence.token();
    evidence.changeEvidence();

    // then
    assertThat(evidence.token()).isNotEqualTo(previous);
  }

  @Test
  @DisplayName("한 블록의 소유자만으로도 재조회 한도를 넘으면 ID를 제공하지 않는다")
  void tooManyOwnersExceedCandidateLimit() {
    context.blockHasTooManyObservedOwners();
    var candidates = context.candidates();
    // then
    assertThat(candidates.status()).isEqualTo(AbsenceStatus.LIMIT_EXCEEDED);
    assertThat(candidates.items()).isEmpty();
  }

  @Test
  @DisplayName("공유 블록의 삭제된 옛 소유자는 재조회 대상에서 제외한다")
  void excludesDeletedOwnerWhenLiveOwnerExists() {
    context.blockHasDeletedOtherOwner();
    var candidates = context.candidates();
    // then
    assertThat(candidates.items())
        .extracting(item -> item.externalId())
        .doesNotContain(AbsenceVerificationScenario.DELETED_OWNER);
  }

  @Test
  @DisplayName("현재 run의 검증 요구가 없으면 정리 증거를 인정하지 않는다")
  void missingRequirementCannotVerify() {
    context
        .ports
        .runs()
        .start(
            AbsenceVerificationScenario.RESUMED_RUN, context.source.id(), context.clock.instant());
    var absences =
        new SnapshotAbsences(
            context.ports.rawItems(), context.ports.runs(), Optional.of(context.bodies));

    // then
    assertThat(
            absences.verify(
                context.source.id(), AbsenceVerificationScenario.RESUMED_RUN, context.scope))
        .contains("missing_requirement");
  }

  @Test
  @DisplayName("후보 token만 일치해도 재조회 제외 증거가 빠지면 정리하지 않는다")
  void everyDirectCandidateNeedsExclusion() {
    context
        .ports
        .runs()
        .recordAbsenceValidation(
            AbsenceVerificationScenario.RUN,
            new RunStore.AbsenceValidation(
                context.scope.fingerprint(), context.candidates().token(), Set.of()));
    var absences =
        new SnapshotAbsences(
            context.ports.rawItems(), context.ports.runs(), Optional.of(context.bodies));

    // then
    assertThat(absences.verify(context.source.id(), AbsenceVerificationScenario.RUN, context.scope))
        .contains("unverified_candidates");
  }

  @Test
  @DisplayName("만료 전 fencing token 재점유는 검증된 전체 완료도 거부한다")
  void replacedClaimRejectsVerifiedCleanup() {
    context.verifyAllCandidatesOutside();
    context.claimWasReplacedBeforeExpiry();
    // when·then
    assertThatThrownBy(context::complete).isInstanceOf(ClaimLostException.class);
    assertThat(context.lastCleanup()).isEmpty();
  }

  @Test
  @DisplayName("이전 run이 관측한 페이지도 새 run의 부재 후보에서는 빼지 않는다")
  void previousRunObservationsAreNotInherited() {
    context.previousRunObservedMissingPage();
    var candidates = context.resumedCandidates();
    // then
    assertThat(candidates.items()).extracting(item -> item.externalId()).contains("unknown");
  }

  @Test
  @DisplayName("정확히 100개인 부재 후보는 전부 검증 대상으로 제공한다")
  void acceptsCandidateLimitExactly() {
    context.fillsCandidateLimit();
    var candidates = context.candidates();
    // then
    assertThat(candidates.status()).isEqualTo(AbsenceStatus.AVAILABLE);
    assertThat(candidates.items()).hasSize(SnapshotScope.MAX_CANDIDATES);
  }

  @Test
  @DisplayName("삭제된 옛 owner는 살아 있는 owner의 제외 검증을 막지 않는다")
  void deletedOtherOwnerDoesNotPreventSnapshotCleanup() {
    context.blockHasDeletedOtherOwner();
    context.verifyOnlyLiveCandidatesOutside();
    var completed = context.complete();
    // then
    assertThat(completed.errorKind()).isEmpty();
  }

  @Test
  @DisplayName("같은 전체 run에서 증분이 관측한 페이지는 최종 정리에서 보존한다")
  void incrementalObservationSurvivesFullCleanup() {
    // given
    context.incrementalPageCommitted();
    context.verifyAllCandidatesOutside();
    // when
    context.complete();
    // then
    assertThat(context.activeIds()).contains(AbsenceVerificationScenario.INCREMENTAL_PAGE);
  }

  @Test
  @DisplayName("같은 run의 관측은 이미 부분 정리한 블록을 복원하지 않는다")
  void observationCannotRestorePartialTombstone() {
    // given
    context.observedBlockWasPartiallyCleaned();
    context.verifyAllCandidatesOutside();
    // when
    context.complete();
    // then
    assertThat(context.ownedBlockIsDeleted()).isTrue();
  }

  @Test
  @DisplayName("전체 정리는 이미 부분 정리한 tombstone을 다시 삭제로 세지 않는다")
  void fullCleanupDoesNotRecountPartialTombstone() {
    // given
    context.observedBlockWasPartiallyCleaned();
    context.verifyAllCandidatesOutside();
    // when
    var completed = context.complete();
    // then
    assertThat(completed.deleted())
        .isEqualTo(AbsenceVerificationScenario.PARTIAL_AND_FULL_DELETIONS);
  }

  @Test
  @DisplayName("v4 전체 gate 뒤 축소 보호는 새 항목에 가려지지 않고 시작 baseline을 쓴다")
  void verifiedFullCleanupUsesRunStartBaseline() {
    // given
    context.newPagesWouldHideOldInventoryLoss();
    context.verifyAllCandidatesOutside();
    // when
    var completed = context.complete();
    // then
    assertThat(completed.errorKind()).contains(SnapshotShrinkGuard.ERROR_KIND);
  }

  @Test
  @DisplayName("정리 보류는 이전 실제 완료 이력을 더 최근으로 덮지 않는다")
  void deferredCleanupPreservesPreviousHistory() {
    // given
    context.previousCleanupExists();
    var previous = context.lastCleanup();
    // when
    context.complete();
    // then
    assertThat(context.lastCleanup()).isEqualTo(previous);
  }

  @Test
  @DisplayName("같은 소유자의 여러 블록 부재는 페이지 상태 한 건으로 합친다")
  void ownedBlockAbsencesCountOnePageState() {
    // given
    context.multipleBlocksHaveOneOwner();
    context.verifyAllCandidatesOutside();
    // when
    context.complete();
    // then
    assertThat(context.discoveredAbsencePages()).isEqualTo(3);
  }

  @Test
  @DisplayName("실제로 정리한 페이지 부재만 full_only 상태 발견으로 기록한다")
  void cleanupRecordsAppliedAbsenceStates() {
    // given
    context.verifyAllCandidatesOutside();
    // when
    context.complete();
    // then
    assertThat(context.discoveredAbsencePages()).isEqualTo(2);
  }

  @Test
  @DisplayName("부재 검증이 보류되면 세대 기준선을 만들지 않는다")
  void deferredCleanupCannotEstablishGenerationBaseline() {
    // when
    context.complete();
    // then
    assertThat(context.hasReconciliationBaseline()).isFalse();
  }

  @Test
  @DisplayName("실제 정리가 성공한 범위 세대만 다음 수집의 기준선이 된다")
  void successfulCleanupEstablishesGenerationBaseline() {
    // given
    context.verifyAllCandidatesOutside();
    // when
    context.complete();
    // then
    assertThat(context.hasReconciliationBaseline()).isTrue();
  }

  @Test
  @DisplayName("부재 검증 결과가 없으면 미관측 원본을 정리하지 않는다")
  void missingValidationPreservesCandidates() {
    // when
    context.complete();
    // then
    assertThat(context.activeIds()).containsExactlyInAnyOrder("seen", "outside", "unknown");
  }

  @Test
  @DisplayName("현재 run의 미관측 직접 재조회 대상만 요약으로 읽는다")
  void readsOnlyCurrentUnobservedCandidates() {
    // when
    var candidates = context.candidates();
    // then
    assertThat(candidates.items())
        .extracting(item -> item.externalId())
        .containsExactlyInAnyOrder("outside", "unknown");
  }

  @Test
  @DisplayName("직접 재조회 대상이 한도를 넘으면 ID를 보내지 않고 보류한다")
  void tooManyCandidatesAreDeferredBeforeRemoteReads() {
    // given
    context.exceedsCandidateLimit();
    // when
    var candidates = context.candidates();
    // then
    assertThat(candidates.status()).isEqualTo(AbsenceStatus.LIMIT_EXCEEDED);
    assertThat(candidates.items()).isEmpty();
  }

  @Test
  @DisplayName("현재 후보 전부의 제외 검증이 일치하면 전체 부재를 정리한다")
  void matchingValidationAllowsCleanup() {
    // given
    context.verifyAllCandidatesOutside();
    // when
    context.complete();
    // then
    assertThat(context.activeIds()).containsExactly("seen");
  }

  @Test
  @DisplayName("소유자를 알 수 없는 블록은 전체 검증 대상을 보류한다")
  void unknownBlockOwnershipDefersVerification() {
    // given
    context.blockHasUnknownOwner();
    // when
    var candidates = context.candidates();
    // then
    assertThat(candidates.status()).isEqualTo(AbsenceStatus.UNKNOWN_OWNERSHIP);
  }

  @Test
  @DisplayName("블록의 옛 소유자가 이미 관측됐어도 현재 본문 증거가 없으면 다시 확인한다")
  void includesUnprovenBodyOwnerAsCandidate() {
    // given
    context.blockHasKnownOwner();
    // when
    var candidates = context.candidates();
    // then
    assertThat(candidates.items()).extracting(item -> item.externalId()).contains("seen");
  }

  @Test
  @DisplayName("검증된 범위 이탈 소유자의 블록은 전체 부재 정리를 허용한다")
  void outsideOwnerProvesBlockAbsence() {
    // given
    context.blockHasKnownOwner();
    context.verifyAllCandidatesOutside();
    // when
    context.complete();
    // then
    assertThat(context.activeIds()).containsExactly("seen");
  }

  @Test
  @DisplayName("미관측 원본이 없어도 현재 run 본문이 미완료면 전체 완료를 보류한다")
  void incompleteBodyDefersSnapshotCompletion() {
    // given
    context.allRawObservedButBodyIncomplete();
    context.verifyAllCandidatesOutside();
    // when
    var completed = context.complete();
    // then
    assertThat(completed.errorKind()).contains(RunCompletion.ABSENCE_DEFERRED);
  }

  @Test
  @DisplayName("거부가 있는 run은 원격 부재 검증을 시작할 후보를 제공하지 않는다")
  void rejectedRunDoesNotOfferCandidates() {
    context.hasRejectedItem();
    var candidates = context.candidates();
    // then
    assertThat(candidates.status()).isEqualTo(AbsenceStatus.UNAVAILABLE);
    assertThat(candidates.items()).isEmpty();
  }

  @Test
  @DisplayName("검증 뒤 새 후보가 생기면 전체 정리를 보류한다")
  void changedCandidateSetDefersCleanup() {
    context.verifyAllCandidatesOutside();
    context.candidateAppears();
    var completed = context.complete();
    // then
    assertThat(completed.errorKind()).contains(RunCompletion.ABSENCE_DEFERRED);
  }

  @Test
  @DisplayName("부재 검증이 보류되면 실제 정리 성공 시각을 만들지 않는다")
  void deferredVerificationDoesNotCreateCleanupHistory() {
    context.complete();
    // then
    assertThat(context.lastCleanup()).isEmpty();
  }

  @Test
  @DisplayName("부재 검증 뒤 점유가 만료되면 늦은 전체 완료를 거부한다")
  void expiredLeaseRejectsVerifiedCleanup() {
    context.verifyAllCandidatesOutside();
    context.claimExpires();
    // then
    assertThatThrownBy(context::complete).isInstanceOf(ClaimLostException.class);
    assertThat(context.lastCleanup()).isEmpty();
  }

  @Test
  @DisplayName("페이지 trash만으로 소유 블록의 전체 부재를 증명하지 않는다")
  void trashedOwnerDoesNotProveBlockAbsence() {
    context.ownerIsTrashedWithoutExclusion();
    var completed = context.complete();
    // then
    assertThat(completed.errorKind()).contains(RunCompletion.ABSENCE_DEFERRED);
    assertThat(context.activeIds()).contains("block:owned");
  }
}
