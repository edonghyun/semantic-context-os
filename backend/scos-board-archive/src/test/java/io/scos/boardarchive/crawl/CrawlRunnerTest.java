package io.scos.boardarchive.crawl;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.store.*;
import io.scos.boardarchive.testsupport.*;
import io.scos.spi.source.SourceInstanceId;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("증분 수집은 커서와 재확인 주기를 지켜 보관한다")
class CrawlRunnerTest {
  private static final Duration POST_COST_60_SECONDS = Duration.ofSeconds(60);
  private static final Duration RUN_BUDGET_100_SECONDS = Duration.ofSeconds(100);
  private static final Duration CROSS_PAGE_BUDGET_230_SECONDS = Duration.ofSeconds(230);
  private static final Duration LISTING_BUDGET_10_SECONDS = Duration.ofSeconds(10);
  private static final Duration PARTIAL_SWEEP_BUDGET_30_SECONDS = Duration.ofSeconds(30);
  private static final Duration AMPLE_BUDGET_ONE_HOUR = Duration.ofHours(1);
  private static final Duration RECHECK_WINDOW_14_DAYS = Duration.ofDays(14);
  private static final Duration NO_SIGNAL_INTERVAL_7_DAYS = Duration.ofDays(7);
  @TempDir Path dir;
  CrawlScenario s;

  @BeforeEach
  void setup() throws Exception {
    s = new CrawlScenario(dir.resolve("archive.db"));
  }

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("첫 실행은 모든 글을 최신순으로 수집한다")
  void firstRunFetchesAllPostsNewestFirst() {
    // given
    s.givenFivePosts();
    // when
    var stats = s.run();
    // then
    assertThat(stats.newPosts()).isEqualTo(5);
    assertThat(s.driver.fetched()).containsExactly("5", "4", "3", "2", "1");
    assertThat(s.archive.store.cursors(s.driver.site(), "a").backfillDone()).isTrue();
  }

  @Test
  @DisplayName("성공한 실행은 끝난 시각을 사이트의 마지막 성공 수집 시각으로 남긴다")
  void successfulRunRecordsFinishTime() {
    // given
    s.givenFivePosts();
    var started = s.archive.clock.instant();
    // when
    s.run();
    // then: 기록은 시작 시각이 아니라 실행이 끝난 뒤의 시계 값이다
    var recorded =
        s.archive.store.lastSucceeded(Set.of(new SourceInstanceId(s.driver.site()))).values();
    assertThat(recorded).containsExactly(s.archive.clock.instant()).allMatch(started::isBefore);
  }

  @Test
  @DisplayName("두 번째 실행은 새 글만 수집한다")
  void secondRunFetchesOnlyNewPosts() {
    // given
    s.givenArchivedPosts();
    s.driver.posts(
        "a",
        List.of(
            s.postAt("6", "00:06"),
            s.postAt("5", "00:05"),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    // when
    var stats = s.run();
    // then
    assertThat(stats.newPosts()).isEqualTo(1);
    assertThat(s.driver.fetched()).containsExactly("6");
  }

  @Test
  @DisplayName("오래된 고정글 뒤 다음 쪽의 새 글도 재확인 창 밖에서 보관한다")
  void pinnedOldPostsDoNotStopFrontScan() {
    // given
    s.givenArchivedPosts();
    s.daysLater(30);
    s.driver.posts(
        "a",
        List.of(
            s.pinnedPostAt("1", "00:01"),
            s.postAt("9", "00:09"),
            s.postAt("8", "00:08"),
            s.postAt("7", "00:07"),
            s.postAt("5", "00:05")));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("9", "8", "7");
  }

  @Test
  @DisplayName("커서 글이 삭제되어도 작성 시각으로 멈춘다")
  void deletedFrontCursorPostStillStops() {
    // given
    s.givenArchivedPosts();
    s.driver.posts(
        "a",
        List.of(
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).isEmpty();
  }

  @Test
  @DisplayName("재확인 창 안 댓글 수 변화는 본문 재수집이다")
  void changedCommentCountTriggersRefetchInsideWindow() {
    // given
    s.givenArchivedPosts();
    s.driver.posts("a", List.of(s.postWithCommentsAt("5", "00:05", 1), s.postAt("4", "00:04")));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("5");
  }

  @Test
  @DisplayName("재확인 창 밖의 변경 신호를 무시한다")
  void changeOutsideWindowIsIgnored() {
    // given
    s.givenArchivedPosts();
    s.daysLater(15);
    s.driver.posts("a", List.of(s.postWithCommentsAt("5", "00:05", 1)));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).isEmpty();
  }

  @Test
  @DisplayName("변경 신호가 없으면 7일 뒤에만 재수집한다")
  void noSignalSiteRefetchesOnlyAfterSevenDays() {
    // given
    s.driver.posts("a", List.of(s.postWithoutSignalAt("5", "00:05")));
    s.run();
    s.driver.clearFetched();
    s.daysLater(6);
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).isEmpty();
  }

  @Test
  @DisplayName("신호 없는 글은 8일 뒤 다시 수집한다")
  void noSignalSiteRefetchesAfterEightDays() {
    // given
    s.driver.posts("a", List.of(s.postWithoutSignalAt("5", "00:05")));
    s.run();
    s.driver.clearFetched();
    s.daysLater(8);
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("5");
  }

  @Test
  @DisplayName("예산으로 나눈 실행은 누락과 본문 중복이 없다")
  void budgetExhaustionResumesBackfillWithoutGaps() {
    // given
    s.givenFivePosts();
    s.driver.pageSize(3);
    s.driver.postCost(POST_COST_60_SECONDS);
    s.settings = CrawlSettings.defaults(RUN_BUDGET_100_SECONDS);
    // when
    s.runCycles(3);
    // then
    assertThat(s.driver.fetched()).containsExactly("5", "4", "3", "2", "1");
    assertThat(s.archive.store.cursors(s.driver.site(), "a").backfillDone()).isTrue();
  }

  @Test
  @DisplayName("앞쪽 삭제로 페이지가 밀려도 뒤쪽 글을 보관한다")
  void backfillOverlapCatchesShiftedPosts() {
    // given: 두 번째 페이지 본문 4에서 중단; 요약 3·2는 다음 실행으로 미룬다
    s.driver.pageSize(3);
    s.driver.posts(
        "a",
        List.of(
            s.postAt("7", "00:07"),
            s.postAt("6", "00:06"),
            s.postAt("5", "00:05"),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    s.driver.postCost(POST_COST_60_SECONDS);
    s.settings = CrawlSettings.defaults(CROSS_PAGE_BUDGET_230_SECONDS);
    s.run();
    // 앞의 네 글 삭제로 읽지 않은 글 1이 첫 페이지로 이동한다
    s.driver.posts(
        "a", List.of(s.postAt("3", "00:03"), s.postAt("2", "00:02"), s.postAt("1", "00:01")));
    // when
    s.runCycles(2);
    // then
    assertThat(s.driver.fetched()).containsExactlyInAnyOrder("7", "6", "5", "4", "3", "2", "1");
  }

  @Test
  @DisplayName("목록 403이면 해당 게시판만 중단한다")
  void listForbiddenStopsOnlyThatBoard() {
    // given
    s.driver.posts("a", List.of(s.postAt("2", "00:02")));
    s.driver.posts("b", List.of(s.postAt("1", "00:01")));
    s.driver.listStatus("a", 403);
    // when
    var stats = s.run();
    // then
    assertThat(stats.stoppedBoards()).containsExactly("a");
    assertThat(s.driver.fetched()).containsExactly("1");
  }

  @Test
  @DisplayName("삭제 결과는 글 상태에 반영한다")
  void deletedResultMarksStatus() {
    // given
    s.driver.posts("a", List.of(s.postAt("1", "00:01")));
    s.driver.result("1", new FetchResult.Deleted());
    // when
    s.run();
    // then
    assertThat(s.status("1")).isEqualTo(PostStatus.DELETED);
  }

  @Test
  @DisplayName("실패한 재확인은 같은 신호에서도 다음 실행에 재시도한다")
  void failedRecheckRetriesSameSignal() {
    // given
    s.givenArchivedPosts();
    s.driver.posts("a", List.of(s.postWithCommentsAt("5", "00:05", 1)));
    s.driver.result("5", new FetchResult.Unknown("temporary"));
    s.run();
    s.driver.clearFetched();
    s.driver.result("5", s.driver.found(s.postAt("5", "00:05")));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("5");
  }

  @Test
  @DisplayName("훑기가 읽은 신호도 다음 본문 재확인에 남는다")
  void sweepDoesNotConsumeChangedSignal() {
    // given
    s.givenArchivedPosts();
    s.driver.posts(
        "a",
        List.of(
            s.postWithCommentsAt("5", "00:05", 1),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    s.sweep();
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("5");
  }

  @Test
  @DisplayName("목록 비용으로 나눈 실행도 뒤쪽으로 전진한다")
  void listingBudgetResumesBeyondAlreadyArchivedPages() {
    // given
    s.driver.posts(
        "a",
        List.of(
            s.postAt("9", "00:09"),
            s.postAt("8", "00:08"),
            s.postAt("7", "00:07"),
            s.postAt("6", "00:06"),
            s.postAt("5", "00:05"),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    s.driver.pageSize(3);
    s.settings = CrawlSettings.defaults(LISTING_BUDGET_10_SECONDS);
    // when
    s.runCycles(12);
    // then
    assertThat(s.driver.fetched())
        .containsExactlyInAnyOrder("9", "8", "7", "6", "5", "4", "3", "2", "1");
  }

  @Test
  @DisplayName("글 HTTP 예외는 실패로 세고 같은 게시판의 다음 글을 수집한다 (§4)")
  void postHttpFailureDoesNotStopBoard() {
    // given
    s.driver.posts("a", List.of(s.postAt("2", "00:02"), s.postAt("1", "00:01")));
    s.driver.postFailure("2", new HttpStatusException(410));
    // when
    var stats = s.run();
    // then
    assertThat(stats.failedPosts()).isEqualTo(1);
    assertThat(stats.stoppedBoards()).isEmpty();
    assertThat(s.status("1")).isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("글 파싱 예외도 실패로 기록하고 다음 글을 수집한다 (§4)")
  void postParserFailureDoesNotStopBoard() {
    // given
    s.driver.posts("a", List.of(s.postAt("2", "00:02"), s.postAt("1", "00:01")));
    s.driver.postFailure("2", new IllegalArgumentException("bad date"));
    // when
    var stats = s.run();
    // then
    assertThat(stats.failedPosts()).isEqualTo(1);
    assertThat(s.driver.fetched()).containsExactly("2", "1");
  }

  @Test
  @DisplayName("목록 파싱 예외는 해당 게시판만 멈추고 실행을 완료 기록한다 (§4)")
  void listParserFailureStopsOnlyItsBoard() {
    // given
    s.driver.posts("a", List.of(s.postAt("2", "00:02")));
    s.driver.posts("b", List.of(s.postAt("1", "00:01")));
    s.driver.listFailure("a", new IllegalArgumentException("bad date"));
    // when
    var stats = s.run();
    // then
    assertThat(stats.stoppedBoards()).containsExactly("a");
    assertThat(s.driver.fetched()).containsExactly("1");
    assertThat(s.archive.store.completedRuns(s.driver.site())).isEqualTo(1);
  }

  @Test
  @DisplayName("훑기 확인 예외는 실행을 기록하고 다음 실행을 막지 않는다 (§4)")
  void confirmationFailureDoesNotLeaveRepeatingSweep() {
    // given
    s.givenMissingPost(new FetchResult.Unknown("temporary"));
    s.driver.postFailure("5", new HttpStatusException(410));
    s.settings =
        new CrawlSettings(
            RECHECK_WINDOW_14_DAYS,
            NO_SIGNAL_INTERVAL_7_DAYS,
            1,
            Optional.empty(),
            AMPLE_BUDGET_ONE_HOUR);
    s.run();
    // when
    var stats = s.run();
    // then
    assertThat(stats.failedPosts()).isEqualTo(1);
    assertThat(s.archive.store.openSweep(s.driver.site())).isEmpty();
    assertThat(s.archive.store.completedRuns(s.driver.site())).isEqualTo(2);
  }

  @Test
  @DisplayName("기존 게시판 새 글은 새 게시판의 긴 첫 수집보다 먼저 보관한다 (§4)")
  void newBoardHistoryDoesNotStarveExistingBoard() {
    // given: a는 신규 게시판, b는 이전 수집 완료
    s.driver.pageSize(3);
    s.driver.posts(
        "a",
        List.of(
            s.postAt("9", "00:09"),
            s.postAt("8", "00:08"),
            s.postAt("7", "00:07"),
            s.postAt("6", "00:06"),
            s.postAt("5", "00:05")));
    s.driver.posts("b", List.of(s.postAt("2", "00:02"), s.postAt("1", "00:01")));
    s.archive.store.upsertBoards(
        s.driver.site(),
        List.of(new Board("a", "a", ""), new Board("b", "b", "")),
        s.archive.clock.instant());
    s.archive.store.storeFound(
        s.driver.site(),
        "b",
        "1",
        s.driver.found(s.postAt("1", "00:01")),
        s.archive.clock.instant());
    s.archive.store.saveCursors(
        s.driver.site(),
        "b",
        new BoardCursors(Optional.of(s.postAt("1", "00:01").key()), Optional.empty(), true));
    s.driver.postCost(POST_COST_60_SECONDS);
    s.settings = CrawlSettings.defaults(RUN_BUDGET_100_SECONDS);
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).startsWith("2");
    assertThat(s.status("2")).isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("앞쪽 커서보다 오래된 글에서 멈추면 뒤 페이지를 요청하지 않는다 (§4)")
  void frontBoundaryLimitsListRequests() {
    // given
    s.givenArchivedPosts();
    s.driver.pageSize(3);
    s.driver.posts(
        "a",
        List.of(
            s.postAt("6", "00:06"),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    s.daysLater(15);
    // when
    s.run();
    // then: FRONT와 RECHECK가 같은 첫 페이지를 재사용한다
    assertThat(s.site.requests())
        .filteredOn(p -> p.startsWith("/list/"))
        .containsExactly("/list/a?offset=0");
  }

  @Test
  @DisplayName("기본 설정은 9회까지 비교하지 않고 10번째 실행에 누락 글을 확인한다 (§4)")
  void tenthRunSchedulesSweep() {
    // given
    s.givenMissingPost(new FetchResult.Deleted());
    // when
    var comparisons = s.runCycles(10).stream().map(RunStats::sweepCompared).toList();
    // then
    assertThat(comparisons)
        .containsExactly(false, false, false, false, false, false, false, false, false, true);
    assertThat(s.driver.fetched()).containsExactly("5");
    assertThat(s.status("5")).isEqualTo(PostStatus.NEEDS_CHECK);
  }

  @Test
  @DisplayName("진행 중 훑기는 주기 사이의 다음 실행에서도 이어진다 (§4)")
  void runnerResumesOpenSweepBeforeNextInterval() {
    // given
    s.givenArchivedPosts();
    s.driver.pageSize(1);
    s.settings =
        new CrawlSettings(
            RECHECK_WINDOW_14_DAYS,
            NO_SIGNAL_INTERVAL_7_DAYS,
            2,
            Optional.empty(),
            PARTIAL_SWEEP_BUDGET_30_SECONDS);
    s.run();
    s.run();
    if (s.archive.store.openSweep(s.driver.site()).isEmpty())
      throw new IllegalStateException("given 준비 실패: 두 번째 실행이 훑기를 남겨야 한다");
    s.settings =
        new CrawlSettings(
            RECHECK_WINDOW_14_DAYS,
            NO_SIGNAL_INTERVAL_7_DAYS,
            2,
            Optional.empty(),
            AMPLE_BUDGET_ONE_HOUR);
    // when
    var stats = s.run();
    // then
    assertThat(stats.sweepCompared()).isTrue();
    assertThat(s.archive.store.openSweep(s.driver.site())).isEmpty();
  }

  @Test
  @DisplayName("게시판 발견 실패는 정상 완료 실행 수에서 제외한다 (§4)")
  void failedDiscoveryDoesNotCountAsCompleted() {
    // given
    s.driver.boardFailure(new IllegalArgumentException("bad board list"));
    // when·then
    assertThatThrownBy(() -> s.run())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("bad board list");
    assertThat(s.archive.store.completedRuns(s.driver.site())).isEqualTo(0);
  }

  @Test
  @DisplayName("삭제 판정 뒤 목록에 돌아온 글은 같은 신호여도 재확인한다 (§4)")
  void reappearingDeletedPostIsFetchedAgain() {
    // given
    s.givenArchivedPosts();
    s.archive.store.markStatus(s.driver.site(), "5", PostStatus.DELETED);
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("5");
    assertThat(s.status("5")).isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("구체 Fetcher 타입과 관계없이 목록과 본문 요청 통계를 기록한다 (§4)")
  void wrappedFetcherStillRecordsRequestCounts() throws Exception {
    // given
    s.givenFivePosts();
    // when
    var requests = s.recordedContentRequestsDuring(s::runViaWrapper);
    // then
    assertThat(requests).isEqualTo(7);
  }

  @Test
  @DisplayName("과거 채우기 재개 중에도 같은 게시판의 새 글을 먼저 보관한다 (§4)")
  void resumedHistoryDoesNotHideNewPosts() {
    // given
    s.givenFivePosts();
    s.driver.pageSize(3);
    s.driver.postCost(POST_COST_60_SECONDS);
    s.settings = CrawlSettings.defaults(RUN_BUDGET_100_SECONDS);
    s.run();
    s.driver.clearFetched();
    s.driver.posts(
        "a",
        List.of(
            s.postAt("6", "00:06"),
            s.postAt("5", "00:05"),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).startsWith("6");
    assertThat(s.status("6")).isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("재확인 재개 중에도 새 글을 앞쪽에서 먼저 보관한다 (§4)")
  void resumedRecheckDoesNotHideNewPosts() {
    // given
    s.givenArchivedPosts();
    s.driver.pageSize(1);
    s.settings = CrawlSettings.defaults(LISTING_BUDGET_10_SECONDS);
    s.run();
    s.driver.posts(
        "a",
        List.of(
            s.postAt("6", "00:06"),
            s.postAt("5", "00:05"),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).startsWith("6");
  }

  @Test
  @DisplayName("예산 때문에 미룬 재등장 글도 다음 실행에서 정상으로 복구한다 (§4)")
  void deferredReappearingPostIsConfirmedNextRun() {
    // given
    s.givenArchivedPosts();
    s.archive.store.markStatus(s.driver.site(), "5", PostStatus.DELETED);
    s.driver.posts(
        "a",
        List.of(
            s.postAt("6", "00:06"),
            s.postAt("5", "00:05"),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    s.driver.postCost(POST_COST_60_SECONDS);
    s.settings = CrawlSettings.defaults(LISTING_BUDGET_10_SECONDS);
    s.run();
    // when
    s.run();
    // then
    assertThat(s.status("5")).isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("최초 삭제 판정을 내린 글이 목록에서도 사라지면 다시 요청하지 않는다 (§4)")
  void confirmedDeletedPostIsNotRetriedFromPendingQueue() {
    // given
    s.driver.posts("a", List.of(s.postAt("1", "00:01")));
    s.driver.result("1", new FetchResult.Deleted());
    s.run();
    s.driver.posts("a", List.of());
    s.driver.clearFetched();
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).isEmpty();
  }
}
