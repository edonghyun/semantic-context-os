package io.scos.boardarchive.crawl;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.FetchResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("증분 수집은 같은 시각의 목록 순서와 재확인 창을 빠짐없이 읽는다 (설계 §4)")
class SameTimeOrderTest {
  private static final Duration POST_COST = Duration.ofSeconds(60);
  private static final Duration PARTIAL_BUDGET = Duration.ofSeconds(100);
  // 재실행의 목록 세 쪽은 요청 간격 3초로 9초여서 PENDING 전에 소진된다.
  private static final Duration LIST_BUDGET = Duration.ofSeconds(7);
  // 목록 세 쪽에 9초: 네 번째 목록까지 미루면 12초여서 본문 예산이 없다.
  private static final Duration RECHECK_BODY_BUDGET = Duration.ofSeconds(10);
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

  @Nested
  @DisplayName("같은 시각의 앞쪽 글이 이미 보관되면")
  class ArchivedFront {
    @BeforeEach
    void archiveFront() {
      s.driver.posts("a", List.of(s.postAt("6383", "00:00")));
      s.run();
      s.driver.clearFetched();
    }

    @Test
    @DisplayName("재확인 창 밖에서도 작은 id 뒤 새 글을 보관한다")
    void sameTimeNewPostAfterSmallerIdIsCollected() {
      // given
      s.daysLater(30);
      s.driver.posts(
          "a",
          List.of(s.postAt("6383", "00:00"), s.postAt("6381", "00:00"), s.postAt("6382", "00:00")));
      // when
      s.run();
      // then
      assertThat(s.bodyFetched("6382")).isTrue();
    }

    @Test
    @DisplayName("재확인 창 밖의 같은 시각이 두 쪽에 걸치면 둘째 쪽 새 글도 보관한다")
    void sameTimeOrderAcrossPageBoundary() {
      // given
      s.daysLater(30);
      s.driver.pageSize(2);
      s.driver.posts(
          "a",
          List.of(s.postAt("6383", "00:00"), s.postAt("6381", "00:00"), s.postAt("6382", "00:00")));
      // when
      s.run();
      // then
      assertThat(s.bodyFetched("6382")).isTrue();
    }

    @Test
    @DisplayName("재확인 창 밖의 같은 시각 구간 뒤 첫 이전 시각 페이지에서 멈춘다")
    void frontStopsAtFirstOlderPageAfterSameTimePages() {
      // given
      s.daysLater(30);
      s.driver.pageSize(2);
      s.driver.posts(
          "a",
          List.of(
              s.postAt("6383", "00:00"),
              s.postAt("6381", "00:00"),
              s.postAt("6382", "00:00"),
              s.postAt("6380", "00:00"),
              s.postAt("-1", Instant.parse("2026-11-16T23:59:00Z")),
              s.postAt("-2", Instant.parse("2026-11-16T23:58:00Z")),
              s.postAt("-3", Instant.parse("2026-11-16T23:57:00Z"))));
      // when
      var requests = s.requestsDuring(s::run);
      // then
      assertThat(requests)
          .filteredOn(p -> p.startsWith("/list/"))
          .containsExactly("/list/a?offset=0", "/list/a?offset=2", "/list/a?offset=4");
    }

    @Test
    @DisplayName("실패한 새 글은 같은 실행의 재확인에서 다시 요청하지 않는다")
    void unseenPostIsAttemptedOnlyOncePerRun() {
      // given
      s.driver.posts("a", List.of(s.postAt("6383", "00:00"), s.postAt("6381", "00:00")));
      s.driver.result("6381", new FetchResult.Unknown("temporary"));
      // when
      s.run();
      // then
      assertThat(s.driver.fetched()).containsExactly("6381");
    }
  }

  @Test
  @DisplayName("변경 없는 두 번째 실행은 목록만 한 번 요청한다")
  void unchangedSecondRunAddsNoRequests() {
    // given
    s.driver.posts(
        "a",
        List.of(s.postAt("6383", "00:00"), s.postAt("6381", "00:00"), s.postAt("6382", "00:00")));
    s.run();
    // when
    var requests = s.requestsDuring(s::run);
    // then
    assertThat(requests)
        .filteredOn(path -> path.startsWith("/list/") || path.startsWith("/posts/"))
        .containsExactly("/list/a?offset=0");
  }

  @Test
  @DisplayName("첫 쪽 중간에서 예산이 끝나도 재개하면 문자열상 큰 id를 보관한다")
  void resumeDoesNotSkipSameTimeUnprocessedPost() {
    // given
    s.driver.posts(
        "a", List.of(s.postAt("9", "00:00"), s.postAt("10", "00:00"), s.postAt("8", "00:00")));
    s.driver.postCost(POST_COST);
    s.settings = CrawlSettings.defaults(PARTIAL_BUDGET);
    s.run();
    if (!s.driver.fetched().equals(List.of("9", "10")))
      throw new IllegalStateException("given 준비 실패: 첫 쪽의 두 본문에서 예산을 소진해야 한다");
    // when
    s.run();
    // then
    assertThat(s.bodyFetched("8")).isTrue();
  }

  @Nested
  @DisplayName("앞쪽 경계보다 이전인 글이 재확인 창 안에 있으면")
  class RecheckWindow {
    @BeforeEach
    void archiveNewerPost() {
      s.driver.posts("a", List.of(s.postAt("5", "00:05")));
      s.run();
      s.driver.clearFetched();
      s.driver.pageSize(1);
      s.driver.posts(
          "a", List.of(s.postAt("5", "00:05"), s.postAt("4", "00:04"), s.postAt("3", "00:03")));
      s.settings = CrawlSettings.defaults(LIST_BUDGET);
    }

    @Test
    @DisplayName("미보관 글은 뒤 목록에서 예산을 소진하기 전에 본문을 가져온다")
    void recheckFetchesUnseenPostInWindow() {
      // given: FRONT는 보관된 4에서 멈추고, 다음 쪽의 3은 RECHECK만 읽는다
      s.givenArchivedPost(s.postAt("4", "00:04"));
      s.givenListingWithinBudget(
          RECHECK_BODY_BUDGET,
          s.postAt("5", "00:05"),
          s.postAt("4", "00:04"),
          s.postAt("3", "00:03"),
          s.postAt("2", "00:02"));
      // when
      s.run();
      // then
      assertThat(s.bodyFetched("3")).isTrue();
    }

    @Test
    @DisplayName("요약만 저장한 글도 재확인 중 본문을 가져온다")
    void recheckFetchesPreviouslyUnfetchedPost() {
      // given
      s.archive.store.recordSummary(
          s.driver.site(), "a", s.postAt("4", "00:04"), s.archive.clock.instant());
      // when
      s.run();
      // then
      assertThat(s.bodyFetched("4")).isTrue();
    }

    @Test
    @DisplayName("신호가 같은 기존 글도 본문 재요청 표시가 있으면 뒤 목록 전에 다시 받는다 (M-6)")
    void recheckFetchesPendingBodyWithUnchangedSignal() {
      // given: FRONT는 4에서 끝나고, RECHECK의 3을 미루면 넷째 목록에서 예산이 끝난다
      s.givenArchivedPost(s.postAt("4", "00:04"));
      s.givenPendingBodyWithSameSignal(s.postWithCommentsAt("3", "00:03", 1));
      s.givenListingWithinBudget(
          RECHECK_BODY_BUDGET,
          s.postAt("5", "00:05"),
          s.postAt("4", "00:04"),
          s.postWithCommentsAt("3", "00:03", 1),
          s.postAt("2", "00:02"));
      // when
      s.run();
      // then
      assertThat(s.driver.fetched()).containsExactly("3");
    }
  }
}
