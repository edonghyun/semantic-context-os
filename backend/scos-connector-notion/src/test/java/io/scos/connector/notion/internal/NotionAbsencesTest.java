package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunStatus;
import io.scos.connector.notion.internal.NotionAbsenceScenario.MissingEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("Notion은 전체 부재 후보의 현재 상태를 재검증한다")
class NotionAbsencesTest {
  final NotionAbsenceScenario context = new NotionAbsenceScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  @DisplayName("부재 후보 복구 도중 생긴 UNKNOWN 자식도 전체 정리 결과 발행을 막는다")
  void deferredChildDuringRecoveryPreventsSnapshotCleanup() {
    context.unknownChildAppearsDuringRecovery();
    var completed = context.reconcile();
    // then
    assertThat(completed.errorKind()).contains(RunCompletion.ABSENCE_DEFERRED);
  }

  @Test
  @DisplayName("이미 활성 소속을 검증한 Search 항목의 거부는 기존 run 거부로 남긴다")
  void knownActiveSearchRejectionIsReported() {
    context.knownActiveSearchRejectionPrecedesCleanup();
    var completed = context.reconcile();
    // then
    assertThat(completed.status()).isEqualTo(RunStatus.SUCCEEDED_WITH_REJECTIONS);
  }

  @ParameterizedTest
  @EnumSource(NotionAbsenceScenario.SearchCandidateHistory.class)
  @DisplayName("활성 소속 증거 없는 Search 후보의 영구 404는 다른 원본의 정리를 막지 않는다")
  void unverifiedSearchRejectionCannotBlockCleanup(
      NotionAbsenceScenario.SearchCandidateHistory history) {
    context.unverifiedSearchRejectionPrecedesCleanup(history);
    context.reconcile();
    // then
    assertThat(context.candidateIsActive()).isFalse();
  }

  @Test
  @DisplayName("범위 미확정 이월 후보 하나가 검증된 다른 부재의 정리를 막지 않는다")
  void unrelatedPendingCandidateCannotBlockCleanup() {
    context.unrelatedCandidateWasDeferred();
    context.reconcile();
    // then
    assertThat(context.candidateIsActive()).isFalse();
  }

  @Test
  @DisplayName("미관측 페이지가 active·범위 안이면 본문을 다시 수집해 전체 정리에서 보존한다")
  void recoversActivePageBeforeCleanup() {
    context.activePageIsInside();
    context.reconcile();
    // then
    assertThat(context.bodyCompleted()).isTrue();
    assertThat(context.candidateIsActive()).isTrue();
  }

  @Test
  @DisplayName("현재 범위 밖으로 이동한 페이지는 제외 증거를 남겨 전체 부재 정리를 허용한다")
  void verifiesMovedPageBeforeCleanup() {
    context.pageMovedOutside();
    context.reconcile();
    // then
    assertThat(context.candidateIsActive()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(MissingEvidence.class)
  @DisplayName("부재 후보의 현재 범위를 검증할 수 없으면 삭제하지 않고 전체 정리를 보류한다")
  void defersUnverifiableCandidate(MissingEvidence reason) {
    context.unverifiablePage(reason);
    var completed = context.reconcile();
    // then
    assertThat(completed.errorKind()).contains(RunCompletion.ABSENCE_DEFERRED);
    assertThat(context.candidateIsActive()).isTrue();
  }

  @Test
  @DisplayName("미관측 database가 active·범위 안이면 metadata를 실제 관측해 보존한다")
  void recoversActiveDatabaseBeforeCleanup() {
    context.activeDatabaseIsInside();
    var completed = context.reconcile();
    // then
    assertThat(completed.errorKind()).isEmpty();
    assertThat(context.candidateIsActive()).isTrue();
  }

  @Test
  @DisplayName("미관측 data source가 active·범위 안이면 metadata를 실제 관측해 보존한다")
  void recoversActiveDataSourceBeforeCleanup() {
    context.activeDataSourceIsInside();
    var completed = context.reconcile();
    // then
    assertThat(completed.errorKind()).isEmpty();
    assertThat(context.candidateIsActive()).isTrue();
  }

  @Test
  @DisplayName("미관측 data source의 복구는 기존 행의 본문 완료까지 이어진다")
  void recoversExistingRowsOfMissingDataSource() {
    context.activeDataSourceIsInside();
    context.reconcile();
    // then
    assertThat(context.recoveredDataSourceRowVersion())
        .contains(NotionDataSourcesScenario.OLD_VERSION);
  }

  @Test
  @DisplayName("복구가 후보 집합을 바꾸면 옛 제외 증거를 버리고 돌아온 페이지를 다시 확인한다")
  void revalidatesExclusionsAfterRecoveryChangesCandidates() {
    context.pageReturnsInsideDuringRecovery();
    context.reconcile();
    // then
    assertThat(context.returningPageIsActive()).isTrue();
  }

  @Test
  @DisplayName("후보가 100개를 넘으면 원격 조회와 검증 결과 저장·전체 정리를 시작하지 않는다")
  void exceedingLimitDoesNotStartVerification() {
    context.exceedsCandidateLimit();
    var completed = context.reconcile();
    // then
    assertThat(context.remoteRequestCount()).isZero();
    assertThat(context.verificationWasStored()).isFalse();
    assertThat(completed.deleted()).isZero();
  }

  @Test
  @DisplayName("요청과 다른 ID의 범위 밖 응답은 제외 증거로 쓰지 않는다")
  void rejectsWrongCandidateIdentity() {
    context.wrongPageIsReturned();
    // when·then
    assertThatThrownBy(context::reconcile).isInstanceOf(NotionProtocolException.class);
    assertThat(context.candidateIsActive()).isTrue();
  }

  @Test
  @DisplayName("동일 세대에서 검증된 페이지의 명시적 trash는 부모가 미확정이어도 적용한다")
  void appliesTrashForPreviouslyVerifiedPage() {
    context.knownPageIsTrashedWithUnknownParent();
    context.reconcile();
    // then
    assertThat(context.candidateIsActive()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = NotionMutations.Resource.class,
      names = {"DATABASE", "DATA_SOURCE"})
  @DisplayName("동일 세대 inventory에서 검증된 container의 명시적 trash는 부모가 미확정이어도 적용한다")
  void appliesTrashForPreviouslyVerifiedContainer(NotionMutations.Resource resource) {
    context.knownContainerIsTrashed(resource);
    context.reconcile();
    // then
    assertThat(context.candidateIsActive()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = NotionMutations.Resource.class,
      names = {"DATABASE", "DATA_SOURCE"})
  @DisplayName("과거 inventory에 있어도 active container의 현재 범위 이탈은 보존 근거가 아니다")
  void doesNotReusePastMembershipForActiveContainer(NotionMutations.Resource resource) {
    context.knownContainerIsActiveOutside(resource);
    context.reconcile();
    // then
    assertThat(context.candidateIsActive()).isFalse();
  }

  @Test
  @DisplayName("현재 범위 이탈을 검증한 소유자의 과거 블록은 전체 부재 정리를 허용한다")
  void outsideOwnerProvesOwnedBlockAbsence() {
    context.blockOwnerMovedOutside();
    context.reconcile();
    // then
    assertThat(context.ownedBlockIsActive()).isFalse();
  }

  @Test
  @DisplayName("이전 run에 tombstone만 남은 페이지도 trash를 재확인해 소유 블록을 정리한다")
  void previouslyTrashedOwnerCanRecoverCleanup() {
    context.previouslyTrashedOwnerHasActiveBlock();
    var completed = context.reconcile();
    // then
    assertThat(context.ownedBlockIsActive()).isFalse();
    assertThat(completed.errorKind()).isEmpty();
  }

  @Test
  @DisplayName("페이지의 명시적 trash는 단독 소유 블록을 정리해 전체 정리를 재개한다")
  void explicitTrashCleansOwnedBlock() {
    context.blockOwnerIsTrashed();
    var completed = context.reconcile();
    // then
    assertThat(context.ownedBlockIsActive()).isFalse();
    assertThat(completed.errorKind()).isEmpty();
  }

  @Test
  @DisplayName("복구마다 후보가 계속 생기면 검증 결과 없이 유한하게 보류한다")
  void continuingCandidateChurnDefersVerification() {
    context.candidatesKeepChanging();
    var completed = context.reconcile();
    // then
    assertThat(context.verificationWasStored()).isFalse();
    assertThat(completed.errorKind()).contains(RunCompletion.ABSENCE_DEFERRED);
  }
}
