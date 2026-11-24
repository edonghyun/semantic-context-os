package io.scos.boardarchive.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("증분 수집은 쪽 안의 비단조 시각을 빠짐없이 방문한다 (설계 §4)")
class NonMonotoneOrderTest {
  private static final Duration POST_COST = Duration.ofSeconds(60);
  private static final Duration PARTIAL_BUDGET = Duration.ofSeconds(100);
  private static final Duration FULL_BUDGET = Duration.ofHours(1);
  private static final CrawlSettings DEPTH_TO_NINE_AM =
      new CrawlSettings(
          Duration.ofDays(14),
          Duration.ofDays(7),
          10,
          Optional.of(Duration.ofHours(15)),
          FULL_BUDGET);
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
  @DisplayName("한 실행에서 시각이 역전되어도 세 글을 모두 보관한다")
  void nonMonotoneTimesInOneRunAreAllVisited() {
    // given: 10:00 → 09:00 → 09:30, 재확인 창 밖
    s.daysLater(30);
    s.driver.posts(
        "a", List.of(s.postAt("600", "10:00"), s.postAt("540", "09:00"), s.postAt("570", "09:30")));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("600", "540", "570");
  }

  @Test
  @DisplayName("최초 깊이보다 이전 글이 있어도 받은 쪽의 나머지 글을 보관한다")
  void backfillFinishesPageAtInitialDepth() {
    // given: 깊이 경계 09:00, 첫 쪽 10:00 → 08:00 → 09:30
    s.settings = DEPTH_TO_NINE_AM;
    s.driver.posts(
        "a",
        List.of(
            s.postAt("600", "10:00"),
            s.postAt("480", "08:00"),
            s.postAt("570", "09:30"),
            s.postAt("450", "07:30")));
    // when
    s.run();
    // then
    assertThat(s.site.requests())
        .filteredOn(path -> path.startsWith("/list/") || path.startsWith("/posts/"))
        .containsExactly("/list/a?offset=0", "/posts/600", "/posts/480", "/posts/570");
  }

  @Test
  @DisplayName("비단조 목록의 변경 없는 두 번째 실행은 추가 요청이 없다")
  void unchangedSecondRunAddsNoRequests() {
    // given
    s.driver.posts(
        "a", List.of(s.postAt("600", "10:00"), s.postAt("540", "09:00"), s.postAt("570", "09:30")));
    s.run();
    // when
    var requests = s.requestsDuring(s::run);
    // then
    assertThat(requests)
        .filteredOn(path -> path.startsWith("/list/") || path.startsWith("/posts/"))
        .containsExactly("/list/a?offset=0");
  }

  @Nested
  @DisplayName("경계 10:00 뒤에 이전 글과 10:30 새 글이 같은 쪽에 있으면")
  class FrontBoundary {
    @BeforeEach
    void archiveBoundary() {
      s.driver.posts("a", List.of(s.postAt("600", "10:00"), s.postAt("540", "09:00")));
      s.run();
      s.daysLater(30);
      s.driver.posts(
          "a",
          List.of(
              s.postAt("660", "11:00"),
              s.postAt("540", "09:00"),
              s.postAt("630", "10:30"),
              s.postAt("510", "08:30")));
    }

    @Test
    @DisplayName("이전 글 뒤의 새 글도 재확인 창 밖에서 보관한다")
    void frontFinishesPageAfterOlderPost() {
      // when
      s.run();
      // then
      assertThat(s.bodyFetched("630")).isTrue();
    }

    @Test
    @DisplayName("경계 이전 글이 나온 쪽까지만 목록을 요청한다")
    void frontDoesNotRequestNextPageAfterOlderPost() {
      // given
      // when
      var requests = s.requestsDuring(s::run);
      // then
      assertThat(requests)
          .filteredOn(p -> p.startsWith("/list/"))
          .containsExactly("/list/a?offset=0");
    }
  }

  @Nested
  @DisplayName("비단조 목록의 첫 쪽 중간에서 본문 예산이 끝나면")
  class ResumeOverlap {
    @BeforeEach
    void suspendAfterTwoBodies() {
      s.driver.posts(
          "a",
          List.of(
              s.postAt("600", "10:00"),
              s.postAt("540", "09:00"),
              s.postAt("570", "09:30"),
              s.postAt("480", "08:00")));
      s.driver.postCost(POST_COST);
      s.settings = CrawlSettings.defaults(PARTIAL_BUDGET);
      s.run();
      if (!s.driver.fetched().equals(List.of("600", "540")))
        throw new IllegalStateException("given 준비 실패: 첫 쪽의 두 본문에서 예산을 소진해야 한다");
      s.daysLater(30);
      s.settings = CrawlSettings.defaults(FULL_BUDGET);
    }

    @Test
    @DisplayName("겹침 쪽의 미처리 글을 보관하고 이미 받은 본문은 다시 요청하지 않는다")
    void resumeRevisitsOverlapWithoutExtraFetch() {
      // when
      s.run();
      // then
      assertThat(s.driver.fetched()).containsExactly("600", "540", "570", "480");
    }

    @Test
    @DisplayName("겹침 재방문은 글마다 revision 하나만 보관한다")
    void overlapDoesNotDuplicateRevisions() throws Exception {
      // when
      s.run();
      // then
      assertThat(s.revisionCounts()).isEqualTo(Map.of("480", 1, "540", 1, "570", 1, "600", 1));
    }
  }

  @Test
  @DisplayName("앞쪽 경계는 마지막 방문 시각이 아닌 최댓값으로 다음 실행을 멈춘다")
  void frontKeepsMaximumKey() {
    // given
    s.driver.posts(
        "a", List.of(s.postAt("540", "09:00"), s.postAt("600", "10:00"), s.postAt("480", "08:00")));
    s.run();
    s.daysLater(30);
    s.driver.pageSize(1);
    s.driver.posts("a", List.of(s.postAt("540", "09:00"), s.postAt("510", "08:30")));
    // when
    var requests = s.requestsDuring(s::run);
    // then
    assertThat(requests)
        .filteredOn(path -> path.startsWith("/list/"))
        .containsExactly("/list/a?offset=0");
  }

  @Test
  @DisplayName("뒤쪽 키는 역전된 마지막 글보다 오래된 최솟값을 보존한다")
  void backKeepsMinimumKey() {
    // given
    s.driver.posts(
        "a", List.of(s.postAt("600", "10:00"), s.postAt("480", "08:00"), s.postAt("540", "09:00")));
    // when
    s.run();
    // then
    assertThat(s.archive.store.cursors(s.driver.site(), "a").back())
        .get()
        .extracting(c -> c.key().postId(), c -> c.driverCursor())
        .containsExactly("480", "");
  }

  @Test
  @DisplayName("쪽 경계 너머 새 글은 재확인 창 안이면 재확인이 보관한다")
  void recheckCatchesNonMonotoneTimeAcrossPages() {
    // given
    s.driver.posts("a", List.of(s.postAt("600", "10:00"), s.postAt("540", "09:00")));
    s.run();
    s.driver.clearFetched();
    s.driver.pageSize(2);
    s.driver.posts(
        "a",
        List.of(
            s.postAt("660", "11:00"),
            s.postAt("540", "09:00"),
            s.postAt("630", "10:30"),
            s.postAt("510", "08:30")));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("660", "630", "510");
  }
}
