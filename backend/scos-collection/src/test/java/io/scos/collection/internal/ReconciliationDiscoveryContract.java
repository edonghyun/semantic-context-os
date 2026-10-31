package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.ReconciliationContext.Path;
import io.scos.spi.connector.ReconciliationKind;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("전체 재조정 발견은 영속 페이지 상태로 집계한다")
public abstract class ReconciliationDiscoveryContract {
  protected ReconciliationDiscoveryScenario context;

  protected abstract ReconciliationDiscoveryScenario newContext();

  @BeforeEach
  void prepare() {
    context = newContext();
  }

  @ParameterizedTest
  @EnumSource(ReconciliationDiscoveryScenario.BodyFact.class)
  @DisplayName("해당 페이지의 upsert 사실이 없으면 전체 본문 비교를 시작하지 않는다")
  void comparisonRequiresPageUpsert(ReconciliationDiscoveryScenario.BodyFact fact) {
    context.compareWithoutPageUpsert(fact);

    // then
    assertThat(context.hasComparison()).isFalse();
  }

  @Test
  @DisplayName("증분 본문은 전체 비교 기준을 만들지 않는다")
  void incrementalBodyDoesNotBeginFullComparison() {
    context.compareIncrementalBody();

    // then
    assertThat(context.hasComparison()).isFalse();
  }

  @Test
  @DisplayName("증분 컨테이너 관측은 전체 비교 대상으로 만들지 않는다")
  void incrementalResourceDoesNotBeginFullComparison() {
    var compared = context.incrementalResourceComparisons();
    // then
    assertThat(compared).isEmpty();
  }

  @Test
  @DisplayName("완료 본문이 현재 raw 버전과 다르면 비교 기준은 unknown이다")
  void mismatchingCompletionIsUnknown() {
    context.completedVersionDoesNotMatchRaw();
    context.reconcile();

    // then
    assertThat(context.classifications()).containsExactly("UNKNOWN");
  }

  @Test
  @DisplayName("한 시간 겹침의 유예 안에 있는 변경은 아직 누락으로 집계하지 않는다")
  void longerOverlapKeepsRecentChangeInGrace() {
    context.overlapIsAnHour();
    context.reconcile();
    // then
    assertThat(
            context
                .summary()
                .count(ReconciliationKind.PAGE, ReconciliationDiscovery.Classification.RECENT))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("십 분 겹침의 유예를 지난 변경은 누락으로 집계한다")
  void shorterOverlapCountsOldEnoughMiss() {
    context.overlapIsTenMinutes();
    context.reconcile();
    // then
    assertThat(context.summary().missedPages()).isEqualTo(1);
  }

  @Test
  @DisplayName("저장 순서가 거절한 오래된 trash 관측은 eligible 분모가 아니다")
  void rejectedStaleTrashIsNotEligible() {
    // given
    context.staleTrashAlsoCompared();
    // when
    context.reconcile();
    // then
    assertThat(context.summary().eligiblePages()).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 tombstone의 전체 PAGE 확인도 eligible 분모에서 빠지지 않는다")
  void unchangedTrashIsIncludedInDenominator() {
    // given
    context.unchangedTrashAlsoCompared();
    // when
    context.reconcile();
    // then
    assertThat(context.summary().eligiblePages()).isEqualTo(2);
  }

  @Test
  @DisplayName("전체가 반영한 container 차이는 페이지와 다른 kind로 집계한다")
  void containerDifferenceHasItsOwnKind() {
    // given
    context.changedContainerRead();
    // when
    context.reconcile();
    // then
    assertThat(
            context
                .summary()
                .count(
                    ReconciliationKind.CONTAINER,
                    ReconciliationDiscovery.Classification.INCREMENTAL_MISS))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("새 run의 동일 버전 증분 확인은 본문 완료 증거와 소속을 바꾸지 않는다")
  void incrementalConfirmationPreservesCompletionProof() {
    // given
    context.stateWasAlreadyCommitted();
    var completed = context.completedBody();
    // when
    context.confirmFromNextIncrementalRun();
    // then
    assertThat(context.completedBody())
        .isEqualTo(completed.readAt(Path.INCREMENTAL, ReconciliationDiscoveryScenario.NOW));
  }

  @Test
  @DisplayName("저장 순서가 거부한 오래된 본문 완료는 새 집계를 만들지 않는다")
  void staleUnappliedCompletionDoesNotCreateDiscovery() {
    // given
    context.pageMetadataAdvancedPastCompletedBody();
    // when
    context.reconcile();
    // then
    assertThat(context.discoveryCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("실제 비교 기록이 없으면 R2 보고 표본을 만들지 않는다")
  void missingComparisonHasNoReportSample() {
    // when
    boolean recorded = context.hasRecordedSample();
    // then
    assertThat(recorded).isFalse();
  }

  @ParameterizedTest
  @EnumSource(ReconciliationDiscovery.Classification.class)
  @DisplayName("영속 원장에서 각 발견 분류의 건수를 복원한다")
  void reportsPersistedClassificationCounts(ReconciliationDiscovery.Classification classification) {
    // given
    context.difference(classification);
    // when
    context.reconcile();
    // then
    assertThat(context.summary().count(ReconciliationKind.PAGE, classification)).isEqualTo(1);
  }

  @Test
  @DisplayName("진행 중인 전체 본문을 증분이 완료한 차이는 recent로 기록한다")
  void overlappingIncrementalCompletionIsRecent() {
    // given
    context.incrementalFinishesFullBody();
    // when
    context.reconcile();
    // then
    assertThat(context.classifications()).containsExactly("RECENT");
  }

  @Test
  @DisplayName("진행 중 증분과 겹친 상태는 eligible 분모에서도 제외한다")
  void overlappingCompletionIsNotEligible() {
    // given
    context.incrementalFinishesFullBody();
    // when
    context.reconcile();
    // then
    assertThat(context.summary().eligiblePages()).isZero();
  }

  @Test
  @DisplayName("두 eligible 상태 중 한 누락의 비율은 절반이다")
  void ratioUsesEligibleStates() {
    // given
    context.unchangedPageAlsoCompared();
    // when
    context.reconcile();
    // then
    assertThat(context.summary().missRatio()).isEqualTo(OptionalDouble.of(0.5));
  }

  @Test
  @DisplayName("eligible 분모가 없으면 누락률은 N/A다")
  void ratioIsUnavailableWithoutDenominator() {
    // given
    context.generationIsBootstrapping();
    // when
    context.reconcile();
    // then
    assertThat(context.summary().missRatio()).isEmpty();
  }

  @Test
  @DisplayName("eligible 상태에 차이가 없으면 누락률은 0이다")
  void unchangedEligibleStateHasZeroRatio() {
    // given
    context.stateWasAlreadyCurrent();
    // when
    context.reconcile();
    // then
    assertThat(context.summary().missRatio()).isEqualTo(OptionalDouble.of(0));
  }

  @Test
  @DisplayName("변경 없는 eligible 페이지도 누락과 같은 커밋의 분모에 포함한다")
  void denominatorIncludesUnchangedEligiblePage() {
    // given
    context.unchangedPageAlsoCompared();
    // when
    context.reconcile();
    // then
    assertThat(context.summary().eligiblePages()).isEqualTo(2);
  }

  @ParameterizedTest
  @EnumSource(ReconciliationDiscoveryScenario.PartialRun.class)
  @DisplayName("거부·재개·실패·정리 보류의 집계는 partial 표본이다")
  void unfinishedFullIsPartial(ReconciliationDiscoveryScenario.PartialRun run) {
    // given
    context.partialRun(run);
    // when
    context.reconcile();
    // then
    assertThat(context.summary().partial()).isTrue();
  }

  @Test
  @DisplayName("오류 없이 끝난 complete_snapshot 집계는 완전 표본이다")
  void successfulFullIsCompleteSample() {
    // when
    context.reconcile();
    // then
    assertThat(context.summary().partial()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(ReconciliationDiscoveryScenario.UnknownEvidence.class)
  @DisplayName("편집 시각·성공 탐색·비교 기준을 알 수 없는 차이는 unknown으로 기록한다")
  void uncertainDifferenceIsUnknown(ReconciliationDiscoveryScenario.UnknownEvidence evidence) {
    // given
    context.unknown(evidence);
    // when
    context.reconcile();
    // then
    assertThat(context.classifications()).containsExactly("UNKNOWN");
  }

  @Test
  @DisplayName("동일 상태의 full 커밋 재시도는 누락 수를 늘리거나 취소하지 않는다")
  void fullCommitRetryCountsStateOnce() {
    // given
    context.stateWasAlreadyCommitted();
    // when
    context.reconcile();
    // then
    assertThat(context.summary().missedPages()).isEqualTo(1);
  }

  @Test
  @DisplayName("최초 세대 구축의 차이는 bootstrap으로 기록한다")
  void bootstrapDifferenceIsNotAnIncrementalMiss() {
    // given
    context.generationIsBootstrapping();
    // when
    context.reconcile();
    // then
    assertThat(context.classifications()).containsExactly("BOOTSTRAP");
  }

  @Test
  @DisplayName("페이지 버전이 같은 본문 차이는 full_only로 기록한다")
  void unchangedPageVersionIsFullOnly() {
    // given
    context.samePageVersionWithChangedBody();
    // when
    context.reconcile();
    // then
    assertThat(context.classifications()).containsExactly("FULL_ONLY");
  }

  @Test
  @DisplayName("직전 성공 탐색 뒤의 최신 변경은 recent로 기록하고 주 누락에서 제외한다")
  void recentEditIsNotAnIncrementalMiss() {
    // given
    context.recentEdit();
    // when
    context.reconcile();
    // then
    assertThat(context.classifications()).containsExactly("RECENT");
  }

  @Test
  @DisplayName("정확히 T_i에서 유예만큼 이전인 미반영 상태는 누락 한 건이다")
  void graceBoundaryCountsAsIncrementalMiss() {
    // when
    context.reconcile();
    // then
    assertThat(context.summary().missedPages()).isEqualTo(1);
  }
}
