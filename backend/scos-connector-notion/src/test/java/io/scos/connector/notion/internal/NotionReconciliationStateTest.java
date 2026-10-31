package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionFixtures.CHILD_PAGE;
import static io.scos.connector.notion.internal.NotionFixtures.DATA_SOURCE;
import static io.scos.connector.notion.internal.NotionFixtures.UNKNOWN_PAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.connector.notion.internal.NotionPendingScanScenario.Transition;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("v4 상태는 확정 확인점과 미완료 탐색을 구분한다")
class NotionReconciliationStateTest {
  static final Instant START = Instant.parse("2026-10-25T00:00:00Z");
  static final Roots ROOTS = NotionFixtures.roots("root");
  static final String COMPACT_PAGE_ID = "11111111222233334444555555555555";
  static final String CANONICAL_PAGE_ID = "11111111-2222-3333-4444-555555555555";
  static final String LATER_BODY_VERSION = "2026-10-25T00:01:00Z";

  static final Roots SHORTER_TIMING =
      new Roots(ROOTS.rootPageIds(), false, Duration.ofMinutes(5), Duration.ofMinutes(10));
  static final Instant ORIGINAL_LOWER_BOUND = Instant.parse("2026-10-24T23:30:00Z");
  static final Instant SHORTER_LOWER_BOUND = Instant.parse("2026-10-24T23:50:00Z");

  @ParameterizedTest
  @ValueSource(strings = {"/incremental/active/scan", "/incremental/containers/0/scan"})
  @DisplayName("시간 설정 변경 뒤 재저장한 scan도 생성 당시 겹침을 보존한다")
  void resavedScanPreservesOriginalOverlap(String pointer) {
    var saved = pendingScansAfterTimingChange();
    // then
    assertThat(saved.at(pointer).path("overlapSeconds").asLong())
        .isEqualTo(ROOTS.overlap().toSeconds());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/incremental/active/scan", "/incremental/containers/0/scan"})
  @DisplayName("시간 설정 변경 뒤 재저장한 scan의 하한이 손상되면 거부한다")
  void rejectsCorruptedResavedLowerBound(String pointer) {
    var saved = pendingScansAfterTimingChange();
    saved.withObject(pointer).put("lowerBound", START.toString());
    var checkpoint = new Checkpoint(NotionReconciliationState.SCHEMA_VERSION, saved.toString());
    // then
    assertThatThrownBy(() -> resumeCheckpoint(checkpoint))
        .isInstanceOf(NotionProtocolException.class);
  }

  private ObjectNode pendingScansAfterTimingChange() {
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.rememberDataSource(NotionPendingScanScenario.DATA_SOURCE, START);
    state.beginRows(
        NotionPendingScanScenario.DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    state.completeRows(
        NotionPendingScanScenario.DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    state.beginRows(
        NotionPendingScanScenario.DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    var resumed = resumeWithShorterTiming(resumeWithShorterTiming(state));
    return (ObjectNode) NotionFixtures.JSON.readTree(resumed.checkpoint().valueJson());
  }

  private NotionReconciliationState resumeCheckpoint(Checkpoint checkpoint) {
    return NotionReconciliationState.read(
        NotionFixtures.JSON,
        SHORTER_TIMING,
        CollectionMode.INCREMENTAL,
        Optional.of(checkpoint),
        SEARCH_FINISHED);
  }

  @Test
  @DisplayName("겹침을 줄여도 진행 중 Search는 저장한 하한을 보존해 재개한다")
  void shorterOverlapPreservesPendingSearchLowerBound() {
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    var resumed = resumeWithShorterTiming(state);
    // then
    assertThat(resumed.beginSearch(SearchPhase.ACTIVE, START).lowerBound())
        .isEqualTo(ORIGINAL_LOWER_BOUND);
  }

  @Test
  @DisplayName("겹침을 줄여도 진행 중 행 scan은 저장한 하한을 보존해 재개한다")
  void shorterOverlapPreservesPendingRowsLowerBound() {
    var state = fresh();
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    state.completeRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, SEARCH_FINISHED);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    var resumed = resumeWithShorterTiming(state);
    // then
    assertThat(resumed.rows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL).lowerBound())
        .contains(ORIGINAL_LOWER_BOUND);
  }

  @Test
  @DisplayName("겹침 변경 전 Search를 끝낸 다음 새 탐색부터 줄인 겹침을 적용한다")
  void nextSearchUsesShorterOverlap() {
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    var resumed = resumeWithShorterTiming(state);
    resumed.searchPage(SearchPhase.ACTIVE, Optional.empty(), List.of());
    resumed.completeSearch(SearchPhase.ACTIVE, SEARCH_FINISHED);
    // then
    assertThat(resumed.beginSearch(SearchPhase.ACTIVE, SEARCH_FINISHED).lowerBound())
        .isEqualTo(SHORTER_LOWER_BOUND);
  }

  @Test
  @DisplayName("줄인 겹침으로 다시 저장하고 재기동해도 진행 중 Search 하한을 잃지 않는다")
  void changedTimingSurvivesAnotherRestart() {
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    var resumed = resumeWithShorterTiming(resumeWithShorterTiming(state));
    // then
    assertThat(resumed.beginSearch(SearchPhase.ACTIVE, SEARCH_FINISHED).lowerBound())
        .isEqualTo(ORIGINAL_LOWER_BOUND);
  }

  @Test
  @DisplayName("행 scan을 끝낸 다음 새 탐색부터 줄인 겹침을 적용한다")
  void nextRowsUseShorterOverlap() {
    var state = fresh();
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    state.completeRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, SEARCH_FINISHED);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    var resumed = resumeWithShorterTiming(state);
    resumed.completeRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, SEARCH_FINISHED);
    // then
    assertThat(
            resumed
                .beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, SEARCH_FINISHED)
                .lowerBound())
        .contains(SHORTER_LOWER_BOUND);
  }

  static final Instant SHORTER_INCREMENTAL_DUE = Instant.parse("2026-10-25T00:05:00Z");

  @Test
  @DisplayName("증분 주기를 줄이면 직전 성공 시작 시각에서 새 주기로 다음 실행을 예약한다")
  void changedIntervalReschedulesNextIncremental() {
    var state = fresh();
    state.beginIncremental(START);
    state.incrementalSucceeded(START);
    var resumed = resumeWithShorterTiming(state);
    // then
    assertThat(resumed.incrementalDue(SHORTER_INCREMENTAL_DUE.minusSeconds(1))).isFalse();
    assertThat(resumed.incrementalDue(SHORTER_INCREMENTAL_DUE)).isTrue();
  }

  @Test
  @DisplayName("변경한 증분 주기를 다시 저장하고 재기동해도 다음 실행 시각이 밀리지 않는다")
  void changedIntervalDoesNotDriftOnRestart() {
    var state = fresh();
    state.beginIncremental(START);
    state.incrementalSucceeded(START);
    var resumed = resumeWithShorterTiming(resumeWithShorterTiming(state));
    // then
    assertThat(resumed.incrementalDue(SHORTER_INCREMENTAL_DUE.minusSeconds(1))).isFalse();
    assertThat(resumed.incrementalDue(SHORTER_INCREMENTAL_DUE)).isTrue();
  }

  @Test
  @DisplayName("첫 증분 전 주기를 바꿔도 최초 실행을 미래로 미루지 않는다")
  void changedIntervalKeepsBootstrapDue() {
    var resumed = resumeWithShorterTiming(fresh());
    // then
    assertThat(resumed.incrementalDue(START)).isTrue();
  }

  @Test
  @DisplayName("새 소스도 설정한 증분 주기가 지난 시각부터 다음 탐색을 실행한다")
  void configuredIntervalSchedulesFreshSource() {
    var state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            SHORTER_TIMING,
            CollectionMode.INCREMENTAL,
            Optional.empty(),
            START);
    state.beginIncremental(START);
    state.incrementalSucceeded(START);
    // then
    assertThat(state.incrementalDue(SHORTER_INCREMENTAL_DUE.minusSeconds(1))).isFalse();
    assertThat(state.incrementalDue(SHORTER_INCREMENTAL_DUE)).isTrue();
  }

  @Test
  @DisplayName("시간 메타데이터가 없던 v4 Search도 기존 겹침 하한으로 재개한다")
  void legacySearchTimingRemainsReadable() {
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    var legacy = (ObjectNode) NotionFixtures.JSON.readTree(state.checkpoint().valueJson());
    legacy.withObject("/incremental/active/scan").remove("overlapSeconds");
    legacy.withObject("/progress").remove("incrementalIntervalSeconds");
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            SHORTER_TIMING,
            CollectionMode.INCREMENTAL,
            Optional.of(new Checkpoint(4, legacy.toString())),
            SEARCH_FINISHED);
    // then
    assertThat(resumed.beginSearch(SearchPhase.ACTIVE, SEARCH_FINISHED).lowerBound())
        .isEqualTo(ORIGINAL_LOWER_BOUND);
  }

  static final Instant SEARCH_FINISHED = START.plusSeconds(40);

  @ParameterizedTest
  @EnumSource(NotionPageRefresh.Path.class)
  @DisplayName("재개한 확인점도 Search와 DS 성공 탐색의 완료 시각·경로를 잃지 않는다")
  void explorationHistorySurvivesRestart(NotionPageRefresh.Path path) {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.completeSearch(SearchPhase.ACTIVE, SEARCH_FINISHED);
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, path, START);
    state.completeRows(DATA_SOURCE, path, SEARCH_FINISHED);
    // when
    var resumed = resume(state);
    // then
    assertThat(resumed.checkpoint()).isEqualTo(state.checkpoint());
  }

  @Test
  @DisplayName("DS 성공 탐색의 완료 시각도 독립 확인점에 보존한다")
  void persistsDataSourceExplorationCompletion() {
    // given
    var state = fresh();
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    // when
    state.completeRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, SEARCH_FINISHED);
    // then
    assertThat(
            NotionFixtures.JSON
                .readTree(state.checkpoint().valueJson())
                .at("/incremental/containers/0/completedAt")
                .asString())
        .isEqualTo(SEARCH_FINISHED.toString());
  }

  @Test
  @DisplayName("성공 탐색의 경로·시작·완료 시각을 같은 세대의 확인점에 보존한다")
  void persistsSuccessfulExplorationCompletion() {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    // when
    state.completeSearch(SearchPhase.ACTIVE, SEARCH_FINISHED);
    // then
    assertThat(
            NotionFixtures.JSON
                .readTree(state.checkpoint().valueJson())
                .at("/incremental/active/completedAt")
                .asString())
        .isEqualTo(SEARCH_FINISHED.toString());
  }

  @Test
  @DisplayName("본문의 child 인계는 UUID 하이픈 표기가 다른 페이지 ID로도 읽는다")
  void readsChildHandoffAcrossUuidRepresentations() {
    var state = fresh();
    var child = new NotionWork.Page(CHILD_PAGE);
    state.bodyFinished(COMPACT_PAGE_ID, START.toString(), List.of(child));
    var handoff = state.bodyHandoff(CANONICAL_PAGE_ID, START.toString());
    // then
    assertThat(handoff).containsExactly(child);
  }

  @Test
  @DisplayName("child 인계의 ACK도 UUID 표기가 달라도 같은 페이지 작업을 닫는다")
  void acknowledgesChildHandoffAcrossUuidRepresentations() {
    var state = fresh();
    state.bodyFinished(COMPACT_PAGE_ID, START.toString(), List.of(new NotionWork.Page(CHILD_PAGE)));
    state.acknowledgeBody(CANONICAL_PAGE_ID, START.toString());
    // then
    assertThat(state.bodyHandoff(COMPACT_PAGE_ID, START.toString())).isEmpty();
  }

  @Test
  @DisplayName("재기동한 child 인계도 UUID 표기가 다른 ID로 읽는다")
  void resumesChildHandoffAcrossUuidRepresentations() {
    var state = fresh();
    var child = new NotionWork.Page(CHILD_PAGE);
    state.bodyFinished(COMPACT_PAGE_ID, START.toString(), List.of(child));
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START);
    // then
    assertThat(resumed.bodyHandoff(CANONICAL_PAGE_ID, START.toString())).containsExactly(child);
  }

  @Test
  @DisplayName("한 페이지의 UUID 표기만 다른 중복 child 인계는 확인점 오류로 거부한다")
  void rejectsDuplicateAliasedHandoffs() {
    var state = fresh();
    state.bodyFinished(COMPACT_PAGE_ID, START.toString(), List.of(new NotionWork.Page(CHILD_PAGE)));
    var value = (ObjectNode) NotionFixtures.JSON.readTree(state.checkpoint().valueJson());
    var duplicate =
        ((ObjectNode) value.path("refreshes").get(0)).deepCopy().put("pageId", CANONICAL_PAGE_ID);
    value.withArray("/refreshes").add(duplicate);
    var checkpoint = new Checkpoint(NotionReconciliationState.SCHEMA_VERSION, value.toString());
    // when·then
    assertThatThrownBy(
            () ->
                NotionReconciliationState.read(
                    NotionFixtures.JSON,
                    ROOTS,
                    CollectionMode.INCREMENTAL,
                    Optional.of(checkpoint),
                    START))
        .isInstanceOf(NotionProtocolException.class);
  }

  @Test
  @DisplayName("새 본문 열거를 시작해도 이미 완료한 버전의 child 인계는 잃지 않는다")
  void newEnumerationPreservesCompletedChildHandoff() {
    var state = fresh();
    var child = new NotionWork.Page(CHILD_PAGE);
    state.bodyFinished("root", START.toString(), List.of(child));
    state.recordBodyProgress(
        "root",
        "2026-10-25T00:01:00Z",
        List.of(new NotionWork.BlockChildren("root", Optional.empty())));
    // then
    assertThat(state.bodyHandoff("root", START.toString())).containsExactly(child);
  }

  @Test
  @DisplayName("이전 본문의 늦은 자식 완료 ACK는 새 버전의 child 인계를 지우지 않는다")
  void oldAcknowledgementPreservesNewerChildHandoff() {
    var state = fresh();
    var child = new NotionWork.Page(CHILD_PAGE);
    state.bodyFinished("root", LATER_BODY_VERSION, List.of(child));
    state.acknowledgeBody("root", START.toString());
    // then
    assertThat(state.bodyHandoff("root", LATER_BODY_VERSION)).containsExactly(child);
  }

  @Test
  @DisplayName("확인점 없는 첫 검색은 기준 구축 시작에서 겹침만큼 되돌린다")
  void bootstrapStartsAtAnchorMinusOverlap() {
    // given
    var state = fresh();
    // when
    var scan = state.beginSearch(SearchPhase.ACTIVE, START);
    // then
    assertThat(scan.lowerBound()).isEqualTo(Instant.parse("2026-10-24T23:30:00Z"));
  }

  @Test
  @DisplayName("빈 성공 탐색도 시작 시각을 확정 확인점으로 남긴다")
  void successfulEmptyScanCommitsStart() {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    // when
    completeSearch(state);
    // then
    assertThat(state.watermark(SearchPhase.ACTIVE)).contains(START);
  }

  @Test
  @DisplayName("다음 탐색 하한은 직전 확정 확인점에서 겹침만큼 되돌린다")
  void nextScanUsesCommittedWatermark() {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, Instant.parse("2026-10-25T01:00:00Z"));
    completeSearch(state);
    // when
    var scan = state.beginSearch(SearchPhase.ACTIVE, Instant.parse("2026-10-25T02:00:00Z"));
    // then
    assertThat(scan.lowerBound()).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
  }

  @Test
  @DisplayName("재기동해도 v4의 확정 확인점을 보존한다")
  void persistsCommittedWatermark() {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    completeSearch(state);
    // when
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START);
    // then
    assertThat(resumed.watermark(SearchPhase.ACTIVE)).contains(START);
  }

  @Test
  @DisplayName("미완료 탐색 재개는 원래 시작과 확정 확인점을 유지한다")
  void unfinishedScanDoesNotAdvanceWatermark() {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    completeSearch(state);
    var interrupted = Instant.parse("2026-10-25T01:00:00Z");
    state.beginSearch(SearchPhase.ACTIVE, interrupted);
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            Instant.parse("2026-10-25T02:00:00Z"));
    // when
    var scan = resumed.beginSearch(SearchPhase.ACTIVE, Instant.parse("2026-10-25T02:00:00Z"));
    // then
    assertThat(scan.startedAt()).isEqualTo(interrupted);
    assertThat(resumed.watermark(SearchPhase.ACTIVE)).contains(START);
  }

  @Test
  @DisplayName("미완료 검색의 다음 커서와 페이지 작업을 함께 보존한다")
  void persistsCursorAndCandidateWork() {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.searchPage(
        SearchPhase.ACTIVE, Optional.of("cursor-2"), List.of(new NotionWork.Page("changed")));
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START);
    // when
    var scan = resumed.beginSearch(SearchPhase.ACTIVE, START);
    // then
    assertThat(scan.cursor()).contains("cursor-2");
    assertThat(scan.work()).containsExactly(new NotionWork.Page("changed"));
  }

  @Test
  @DisplayName("후보 본문이 남으면 확정 확인점을 올리지 않는다")
  void pendingWorkBlocksWatermark() {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.searchPage(SearchPhase.ACTIVE, Optional.empty(), List.of(new NotionWork.Page("changed")));
    // when·then
    assertThatThrownBy(() -> completeSearch(state)).isInstanceOf(IllegalStateException.class);
    assertThat(state.watermark(SearchPhase.ACTIVE)).isEmpty();
  }

  @Test
  @DisplayName("검색의 다음 페이지가 남으면 확인점을 확정하지 않는다")
  void nextCursorBlocksWatermark() {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.searchPage(SearchPhase.ACTIVE, Optional.of("cursor-2"), List.of());
    // when·then
    assertThatThrownBy(() -> completeSearch(state)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("검색 후보 완료는 발견한 자식 작업을 이어 간다")
  void advancesCandidateWork() {
    // given
    var state = fresh();
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.searchPage(SearchPhase.ACTIVE, Optional.empty(), List.of(new NotionWork.Page("parent")));
    // when
    state.completedSearchWork(SearchPhase.ACTIVE, List.of(new NotionWork.Page(CHILD_PAGE)));
    // then
    assertThat(state.beginSearch(SearchPhase.ACTIVE, START).work())
        .containsExactly(new NotionWork.Page(CHILD_PAGE));
  }

  @Test
  @DisplayName("만료 커서는 확정 하한을 유지한 채 처음부터 다시 찾는다")
  void expiredCursorRestartsAtSameLowerBound() {
    // given
    var state = fresh();
    var before = state.beginSearch(SearchPhase.ACTIVE, START);
    state.searchPage(
        SearchPhase.ACTIVE, Optional.of("expired"), List.of(new NotionWork.Page("candidate")));
    // when
    state.restartSearch(SearchPhase.ACTIVE);
    // then
    var restarted = state.beginSearch(SearchPhase.ACTIVE, START);
    assertThat(restarted.lowerBound()).isEqualTo(before.lowerBound());
    assertThat(restarted.cursor()).isEmpty();
    assertThat(restarted.work()).containsExactly(new NotionWork.Page("candidate"));
  }

  @Test
  @DisplayName("v3 중간 큐는 incremental로 이행해 그대로 잇는다")
  void migratesV3MiddleWithoutSnapshotPromotion() {
    // given
    var middle =
        new Checkpoint(
            3,
            """
        {"mode":"roots","rootPageIds":["root"],"stage":"traversal",
         "pendingWork":[{"kind":"block_children","blockId":"root","cursor":"blocks-2"}]}
        """);
    // when
    var state =
        NotionReconciliationState.read(
            NotionFixtures.JSON, ROOTS, CollectionMode.INCREMENTAL, Optional.of(middle), START);
    // then
    assertThat(state.coverage()).isEqualTo(CollectionCoverage.INCREMENTAL);
    assertThat(state.fullCheckpoint()).contains(middle);
  }

  @Test
  @DisplayName("최초 기준 구축은 시드부터 전체 사이클을 계획한다")
  void bootstrapPlansFullCycle() {
    // when
    var state = fresh();
    // then
    assertThat(state.coverage()).isEqualTo(CollectionCoverage.COMPLETE_SNAPSHOT);
    assertThat(
            NotionRootTraversal.resume(NotionFixtures.JSON, ROOTS, state.fullCheckpoint())
                .startsCycle())
        .isTrue();
  }

  @ParameterizedTest
  @MethodSource("middleCheckpoints")
  @DisplayName("SNAPSHOT은 v3·v4 중간 전체 큐를 시드부터 다시 시작한다")
  void snapshotRestartsFromSeeds(Checkpoint middle) {
    // when
    var state =
        NotionReconciliationState.read(
            NotionFixtures.JSON, ROOTS, CollectionMode.SNAPSHOT, Optional.of(middle), START);
    // then
    assertThat(state.coverage()).isEqualTo(CollectionCoverage.COMPLETE_SNAPSHOT);
    assertThat(
            NotionRootTraversal.resume(NotionFixtures.JSON, ROOTS, state.fullCheckpoint())
                .startsCycle())
        .isTrue();
  }

  static Stream<Checkpoint> middleCheckpoints() {
    var middle =
        new Checkpoint(
            3,
            """
        {"mode":"roots","rootPageIds":["root"],"stage":"traversal",
         "pendingWork":[{"kind":"block_children","blockId":"root","cursor":"blocks-2"}]}
        """);
    var migrated =
        NotionReconciliationState.read(
                NotionFixtures.JSON, ROOTS, CollectionMode.INCREMENTAL, Optional.of(middle), START)
            .checkpoint();
    return Stream.of(middle, migrated);
  }

  @Test
  @DisplayName("전체 완주 뒤 다음 증분은 새 전체를 자동 시작하지 않는다")
  void completedFullLeavesIncrementalOnlyPlan() {
    // given
    var state = fresh();
    var traversal = NotionRootTraversal.resume(NotionFixtures.JSON, ROOTS, state.fullCheckpoint());
    state.fullProgress(traversal.completeWork(Optional.empty(), List.of()).checkpoint());
    // when
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START);
    // then
    assertThat(resumed.coverage()).isEqualTo(CollectionCoverage.INCREMENTAL);
    assertThat(resumed.fullCheckpoint()).isEmpty();
    assertThat(resumed.watermark(SearchPhase.ACTIVE)).isEmpty();
  }

  @Test
  @DisplayName("지원하지 않는 체크포인트 버전은 실행 계획 전에 거부한다")
  void rejectsUnsupportedVersion() {
    // given
    var unsupported =
        new Checkpoint(
            NotionReconciliationState.SCHEMA_VERSION + 1, fresh().checkpoint().valueJson());
    // when·then
    assertThatThrownBy(
            () ->
                NotionReconciliationState.read(
                    NotionFixtures.JSON,
                    ROOTS,
                    CollectionMode.INCREMENTAL,
                    Optional.of(unsupported),
                    START))
        .isInstanceOf(NotionProtocolException.class);
  }

  @Test
  @DisplayName("v4의 루트 설정이 바뀌면 기존 메시지로 거부한다")
  void rejectsChangedRoots() {
    // given
    var checkpoint = fresh().checkpoint();
    var changedRoots = NotionFixtures.roots("another-root");
    // when·then
    assertThatThrownBy(
            () ->
                NotionReconciliationState.read(
                    NotionFixtures.JSON,
                    changedRoots,
                    CollectionMode.INCREMENTAL,
                    Optional.of(checkpoint),
                    START))
        .isInstanceOf(NotionProtocolException.class)
        .hasMessage(NotionProtocolException.ROOTS_CHANGED);
  }

  @ParameterizedTest
  @MethodSource("invalidV4Checkpoints")
  @DisplayName("잘못된 v4 봉투·시각·지문은 체크포인트 오류로 거부한다")
  void rejectsMalformedV4(Checkpoint invalid) {
    // when·then
    assertThatThrownBy(
            () ->
                NotionReconciliationState.read(
                    NotionFixtures.JSON,
                    ROOTS,
                    CollectionMode.INCREMENTAL,
                    Optional.of(invalid),
                    START))
        .isInstanceOf(NotionProtocolException.class)
        .hasMessage(NotionProtocolException.INVALID_CHECKPOINT);
  }

  static Stream<Checkpoint> invalidV4Checkpoints() {
    var values = new ArrayList<String>();
    var unknown = checkpointValue();
    unknown.put("unexpected", true);
    values.add(unknown.toString());
    var missing = checkpointValue();
    missing.remove("progress");
    values.add(missing.toString());
    var badDate = checkpointValue();
    badDate.withObject("/progress").put("baselineStartedAt", "invalid-date");
    values.add(badDate.toString());
    var badFingerprint = checkpointValue();
    badFingerprint.withObject("/scope").put("configFingerprint", "tampered");
    values.add(badFingerprint.toString());
    var badPath = checkpointValue();
    badPath.withObject("/incremental").put("active", "not-an-object");
    values.add(badPath.toString());

    var badRootType = checkpointValue();
    badRootType.withObject("/scope").putArray("rootPageIds").add(true);
    values.add(badRootType.toString());
    var badTraversal = checkpointValue();
    badTraversal
        .withObject("/traversal")
        .putArray("pendingWork")
        .addObject()
        .put("kind", UNKNOWN_PAGE);
    values.add(badTraversal.toString());
    var scanning = fresh();
    scanning.beginSearch(SearchPhase.ACTIVE, START);
    var badBound = (ObjectNode) NotionFixtures.JSON.readTree(scanning.checkpoint().valueJson());
    badBound.withObject("/incremental/active/scan").put("lowerBound", "2026-10-25T01:00:00Z");
    values.add(badBound.toString());
    values.add("{");
    return values.stream().map(value -> new Checkpoint(4, value));
  }

  private static ObjectNode checkpointValue() {
    return (ObjectNode) NotionFixtures.JSON.readTree(fresh().checkpoint().valueJson());
  }

  @Test
  @DisplayName("증분 큐 진행은 실제 자식 완료 ACK 없이 본문 인계를 지우지 않는다")
  void acknowledgesIncrementalChildHandoff() {
    // given
    var state = fresh();
    var child = new NotionWork.Page(CHILD_PAGE);
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.searchPage(SearchPhase.ACTIVE, Optional.empty(), List.of(new NotionWork.Page("root")));
    state.bodyFinished("root", START.toString(), List.of(child));
    // when
    state.completedSearchWork(SearchPhase.ACTIVE, List.of(child));
    // then
    assertThat(state.bodyHandoff("root", START.toString())).containsExactly(child);
    assertThat(state.beginSearch(SearchPhase.ACTIVE, START).work()).containsExactly(child);
  }

  @Test
  @DisplayName("전체 큐 진행은 실제 자식 완료 ACK 없이 본문 인계를 지우지 않는다")
  void acknowledgesFullChildHandoff() {
    // given
    var state = fresh();
    var child = new NotionWork.Page(CHILD_PAGE);
    state.bodyFinished("root", START.toString(), List.of(child));
    var traversal = NotionRootTraversal.resume(NotionFixtures.JSON, ROOTS, state.fullCheckpoint());
    // when
    state.fullProgress(traversal.completeWork(Optional.empty(), List.of(child)).checkpoint());
    // then
    assertThat(state.bodyHandoff("root", START.toString())).containsExactly(child);
    assertThat(
            NotionRootTraversal.resume(NotionFixtures.JSON, ROOTS, state.fullCheckpoint()).next())
        .isEqualTo(new NotionRootTraversal.Step.Work(child));
  }

  @Test
  @DisplayName("다음 행 응답은 이전 응답의 거부 재시도 작업을 보존한다")
  void nextRowResponsePreservesUnfinishedWork() {
    // given
    var context = new NotionPendingScanScenario();
    // when
    context.nextRowResponse();
    // then
    assertThat(context.pendingRows()).containsExactly(NotionPendingScanScenario.REJECTED);
  }

  @Test
  @DisplayName("다음 검색 응답은 이전 응답의 거부 재시도 작업을 보존한다")
  void nextSearchResponsePreservesUnfinishedWork() {
    // given
    var context = new NotionPendingScanScenario();
    // when
    context.nextSearchResponse();
    // then
    assertThat(context.pendingSearch()).containsExactly(NotionPendingScanScenario.REJECTED);
  }

  @ParameterizedTest
  @EnumSource(Transition.class)
  @DisplayName("커서 재시작과 생성 시각 창 전환은 미완료 작업을 보존한다")
  void scanTransitionPreservesUnfinishedWork(Transition transition) {
    // given
    var context = new NotionPendingScanScenario();
    // when
    context.transition(transition);
    // then
    assertThat(context.pending(transition)).containsExactly(NotionPendingScanScenario.REJECTED);
  }

  private static NotionReconciliationState fresh() {
    return NotionReconciliationState.read(
        NotionFixtures.JSON, ROOTS, CollectionMode.INCREMENTAL, Optional.empty(), START);
  }

  @Test
  @DisplayName("data source별 확정 확인점은 v4 재기동 뒤에도 유지한다")
  void persistsDataSourceWatermark() {
    // given
    var state = fresh();
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    state.completeRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, SEARCH_FINISHED);
    // when
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START.plusSeconds(1));
    // then
    assertThat(resumed.rowWatermark(DATA_SOURCE)).contains(START);
  }

  @Test
  @DisplayName("행 응답의 커서와 미완료 본문은 같은 확인점으로 재개한다")
  void persistsPendingRowWork() {
    // given
    var state = fresh();
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.of("rows-next"),
        Optional.of(START),
        true,
        List.of(new NotionWork.Page("row")));
    // when
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START);
    // then
    assertThat(resumed.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START).cursor())
        .contains("rows-next");
    assertThat(resumed.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START).work())
        .containsExactly(new NotionWork.Page("row"));
  }

  @Test
  @DisplayName("검증된 database 목록은 원본 저장소 없이 재기동 후 점검한다")
  void persistsDatabaseInventory() {
    // given
    var state = fresh();
    state.rememberDatabase("db", START);
    // when
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START);
    // then
    assertThat(resumed.databases()).containsExactly("db");
  }

  @Test
  @DisplayName("확정 확인점보다 앞당겨 위조한 행 하한은 재개하지 않는다")
  void rejectsChangedRowsLowerBound() {
    // given
    var state = fresh();
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    state.completeRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, SEARCH_FINISHED);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    var saved = NotionFixtures.JSON.readTree(state.checkpoint().valueJson());
    ((ObjectNode) saved.at("/incremental/containers/0/scan")).put("lowerBound", START.toString());
    // when·then
    assertThatThrownBy(
            () ->
                NotionReconciliationState.read(
                    NotionFixtures.JSON,
                    ROOTS,
                    CollectionMode.INCREMENTAL,
                    Optional.of(new Checkpoint(4, saved.toString())),
                    START))
        .isInstanceOf(NotionProtocolException.class);
  }

  @Test
  @DisplayName("재기동과 전체 조각 저장은 다음 증분 시각을 미루지 않는다")
  void restoresDueAfterFullProgress() {
    // given
    var state = fresh();
    state.beginIncremental(START);
    state.incrementalSucceeded(START);
    var traversal = NotionRootTraversal.resume(NotionFixtures.JSON, ROOTS, state.fullCheckpoint());
    state.fullProgress(
        traversal
            .completeWork(Optional.empty(), List.of(new NotionWork.Page(CHILD_PAGE)))
            .checkpoint());
    // when
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START.plusSeconds(1));
    // then
    assertThat(
            NotionFixtures.JSON
                .readTree(resumed.checkpoint().valueJson())
                .at("/progress/nextIncrementalAt")
                .asString())
        .isEqualTo(NotionIncrementalScenario.NEXT_EXPLORATION.toString());
  }

  @Test
  @DisplayName("이전 예약 시각 전의 미완료 증분도 재기동하면 즉시 재개한다")
  void pendingExplorationRemainsDueAcrossRestart() {
    // given
    var state = fresh();
    state.beginIncremental(START);
    state.incrementalSucceeded(START);
    state.beginIncremental(START.plusSeconds(1));
    // when
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START.plusSeconds(2));
    // then
    assertThat(resumed.incrementalDue(START.plusSeconds(2))).isTrue();
  }

  @Test
  @DisplayName("전체 완료는 이미 시작한 신규 DS 증분의 고정 하한을 바꾸지 않는다")
  void fullCompletionPreservesPendingBootstrapLowerBound() {
    // given
    var state = fresh();
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.FULL, START.plusSeconds(1));
    state.completeRows(DATA_SOURCE, NotionPageRefresh.Path.FULL, SEARCH_FINISHED);
    // when
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            START.plusSeconds(2));
    // then
    assertThat(
            resumed.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, START).lowerBound())
        .isEmpty();
  }

  @Test
  @DisplayName("SNAPSHOT은 저장된 전체 행 커서를 버리고 새 백필을 시작한다")
  void snapshotDiscardsFullRowsProgress() {
    // given
    var state = fresh();
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.FULL, START);
    state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.FULL,
        Optional.of("old-full-cursor"),
        Optional.of(START),
        false,
        List.of());
    // when
    var snapshot =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.SNAPSHOT,
            Optional.of(state.checkpoint()),
            START.plusSeconds(1));
    // then
    assertThat(snapshot.beginRows(DATA_SOURCE, NotionPageRefresh.Path.FULL, START).cursor())
        .isEmpty();
  }

  private void completeSearch(NotionReconciliationState state) {
    state.completeSearch(
        SearchPhase.ACTIVE, state.beginSearch(SearchPhase.ACTIVE, START).startedAt());
  }

  private NotionReconciliationState resumeWithShorterTiming(NotionReconciliationState state) {
    return NotionReconciliationState.read(
        NotionFixtures.JSON,
        SHORTER_TIMING,
        CollectionMode.INCREMENTAL,
        Optional.of(state.checkpoint()),
        SEARCH_FINISHED);
  }

  private NotionReconciliationState resume(NotionReconciliationState state) {
    return NotionReconciliationState.read(
        NotionFixtures.JSON,
        ROOTS,
        CollectionMode.INCREMENTAL,
        Optional.of(state.checkpoint()),
        SEARCH_FINISHED);
  }
}
