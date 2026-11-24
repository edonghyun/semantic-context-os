package io.scos.boardarchive.crawl;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.store.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("전체 훑기는 모든 게시판이 완료되어야 삭제를 확인한다")
class SweepPassTest {
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
  @DisplayName("보관한 글의 첫 삭제 확인은 확인 필요를 유지한다")
  void firstDeletedVerdictKeepsNeedsCheck() {
    // given
    s.givenMissingPost(new FetchResult.Deleted());
    // when
    boolean compared = s.sweep();
    // then
    assertThat(compared).isTrue();
    assertThat(s.status("5")).isEqualTo(PostStatus.NEEDS_CHECK);
  }

  @Test
  @DisplayName("하루 뒤 비교에서도 삭제로 확인되면 삭제가 된다")
  void deletedAgainADayLaterDeletes() {
    // given
    s.givenMissingPostSeenDeletedOnce();
    s.daysLater(1);
    // when
    s.sweep();
    // then
    assertThat(s.status("5")).isEqualTo(PostStatus.DELETED);
  }

  @Test
  @DisplayName("하루가 지나기 전 다음 비교의 삭제 확인은 확인 필요를 유지한다")
  void deletedAgainWithinADayKeepsNeedsCheck() {
    // given
    s.givenMissingPostSeenDeletedOnce();
    s.hoursLater(23);
    // when
    s.sweep();
    // then
    assertThat(s.status("5")).isEqualTo(PostStatus.NEEDS_CHECK);
  }

  @Test
  @DisplayName("두 확인 사이에 본문이 보이면 삭제 확인을 처음부터 센다")
  void foundBetweenVerdictsRestartsCount() {
    // given
    s.givenMissingPostDeletedThenFound();
    s.daysLater(1);
    // when
    s.sweep();
    // then
    assertThat(s.status("5")).isEqualTo(PostStatus.NEEDS_CHECK);
  }

  @Test
  @DisplayName("두 확인 사이에 목록에 다시 보이면 삭제 확인을 처음부터 센다")
  void relistedBetweenVerdictsRestartsCount() {
    // given
    s.givenMissingPostDeletedThenRelisted();
    s.daysLater(1);
    // when
    s.sweep();
    // then
    assertThat(s.status("5")).isEqualTo(PostStatus.NEEDS_CHECK);
  }

  @Test
  @DisplayName("본문을 보관하지 않은 누락 글은 한 번의 삭제 확인으로 삭제가 된다")
  void unfetchedPostDeletesOnFirstVerdict() {
    // given
    s.givenMissingUnfetchedPost("6", "00:06");
    // when
    s.sweep();
    // then
    assertThat(s.status("6")).isEqualTo(PostStatus.DELETED);
  }

  @Test
  @DisplayName("목록에서 사라진 글은 보관본 URL에서 다시 확인한다")
  void vanishedPostIsRecheckedAtArchivedUrl() {
    // given
    s.givenMissingPostArchivedAt("/view/5");
    // when
    List<String> requests = s.requestsDuring(s::sweep);
    // then
    assertThat(requests).contains("/view/5").doesNotContain("/posts/5");
  }

  @Test
  @DisplayName("본문을 보관하지 않은 누락 글은 기본 경로로 확인한다")
  void vanishedPostWithoutRevisionUsesDriverRoute() {
    // given
    s.givenMissingUnfetchedPost("6", "00:06");
    // when
    List<String> requests = s.requestsDuring(s::sweep);
    // then
    assertThat(requests).contains("/posts/6");
  }

  @Test
  @DisplayName("확인 수집이 성공하면 정상으로 돌아온다")
  void foundOnConfirmationReturnsToNormal() {
    // given
    s.givenMissingPost(s.driver.found(s.postAt("5", "00:05")));
    // when
    s.sweep();
    // then
    assertThat(s.status("5")).isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("판정 불명은 확인 필요를 유지한다")
  void unknownKeepsNeedsCheck() {
    // given
    s.givenMissingPost(new FetchResult.Unknown("temporary"));
    // when
    s.sweep();
    // then
    assertThat(s.status("5")).isEqualTo(PostStatus.NEEDS_CHECK);
  }

  @Test
  @DisplayName("다른 게시판으로 이동한 글은 누락이 아니다")
  void movedPostIsNotMissing() {
    // given
    s.givenMissingPost(new FetchResult.Deleted());
    s.driver.posts("b", List.of(s.postAt("5", "00:05")));
    // when
    s.sweep();
    // then
    assertThat(s.status("5")).isEqualTo(PostStatus.NORMAL);
    assertThat(s.driver.fetched()).isEmpty();
  }

  @Test
  @DisplayName("한 게시판이 금지되면 훑기 전체를 폐기한다")
  void sweepWithBlockedBoardIsDiscarded() {
    // given
    s.givenMissingPost(new FetchResult.Deleted());
    s.driver.posts("b", List.of());
    s.driver.listStatus("b", 403);
    // when
    boolean compared = s.sweep();
    // then
    assertThat(compared).isFalse();
    assertThat(s.status("5")).isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("시간으로 중단한 훑기는 다음 실행에서 이어진다")
  void interruptedSweepResumesNextRun() {
    // given
    s.givenArchivedPosts();
    s.shortSweep();
    // when
    boolean compared = s.sweep();
    // then
    assertThat(compared).isTrue();
    assertThat(s.archive.store.openSweep(s.driver.site())).isEmpty();
  }

  @Test
  @DisplayName("중단한 훑기는 비교하지 않고 열린 상태를 유지한다")
  void interruptedSweepDoesNotCompare() {
    // given
    s.givenArchivedPosts();
    // when
    boolean compared = s.shortSweep();
    // then
    assertThat(compared).isFalse();
    assertThat(s.archive.store.openSweep(s.driver.site())).isPresent();
  }

  @Test
  @DisplayName("훑기 시작 후 처음 보관한 글은 누락에서 제외한다")
  void postFirstSeenAfterSweepStartIsExcluded() {
    // given
    s.givenArchivedPosts();
    s.archive.store.startSweep(s.driver.site(), s.archive.clock.instant());
    s.daysLater(1);
    s.archive.store.storeFound(
        s.driver.site(),
        "a",
        "6",
        s.driver.found(s.postAt("6", "00:06")),
        s.archive.clock.instant());
    // when
    s.sweep();
    // then
    assertThat(s.status("6")).isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("게시판이 사라지면 열린 훑기를 폐기한다")
  void removedBoardDiscardsSweep() {
    // given
    s.givenArchivedPosts();
    s.driver.posts("b", List.of(s.postAt("6", "00:06")));
    s.shortSweep();
    s.driver.removeBoard("b");
    // when
    boolean compared = s.sweep();
    // then
    assertThat(compared).isFalse();
    assertThat(s.archive.store.openSweep(s.driver.site())).isEmpty();
  }

  @Test
  @DisplayName("확인 단계도 예산을 나눠 중복 없이 재개한다")
  void confirmationBudgetResumesWithoutRepeatingFoundPosts() {
    // given
    s.givenArchivedPosts();
    s.driver.posts("a", List.of());
    // when
    sweepInEightSlices();
    // then
    assertThat(s.driver.fetched()).containsExactlyInAnyOrder("1", "2", "3", "4", "5");
  }

  private void sweepInEightSlices() {
    for (int i = 0; i < 8; i++) if (s.sweepForSeconds(7)) return;
  }

  @Test
  @DisplayName("훑기 시작 전에 사라진 게시판도 비교하지 않는다")
  void boardRemovedBeforeSweepIsNotCompared() {
    // given
    s.givenArchivedPosts();
    s.driver.removeBoard("a");
    // when
    boolean compared = s.sweep();
    // then
    assertThat(compared).isFalse();
    assertThat(s.status("5")).isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("차단된 게시판 때문에 폐기한 훑기는 사유와 게시판을 보관한다 (§4)")
  void discardedSweepRecordsBlockedBoard() throws Exception {
    // given
    s.givenArchivedPosts();
    s.driver.listStatus("a", 403);
    // when
    s.sweep();
    // then
    assertThat(s.archive.sweepDiscardReason()).isEqualTo("LIST_HTTP_403:a");
  }
}
