package io.scos.boardarchive.store;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.PostKey;
import io.scos.boardarchive.testsupport.ArchiveScenario;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("보관소는 정규화 내용의 이력과 현재 상태를 보존한다")
class ArchiveStoreTest {
  private static final Duration LATER = Duration.ofHours(25);
  @TempDir Path dir;
  ArchiveScenario s;

  @BeforeEach
  void setup() {
    s = new ArchiveScenario(dir.resolve("archive.db"));
  }

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("첫 수집만 새 revision을 만든다")
  void firstFetchCreatesRevision() throws Exception {
    s.givenPost();
    // when
    boolean changed = s.save(s.found("Body"));
    // then
    assertThat(changed).isFalse();
    assertThat(s.revisionCounts()).isEqualTo(Map.of("42", 1));
  }

  @Test
  @DisplayName("raw만 바뀌면 revision을 만들지 않는다")
  void volatileRawChangeDoesNotCreateRevision() throws Exception {
    s.givenPost();
    // when
    boolean changed =
        s.save(s.found("Body", "<article views='99'>Body</article>", List.of(), true));
    // then
    assertThat(changed).isFalse();
    assertThat(s.revisionCounts()).isEqualTo(Map.of("42", 1));
  }

  @Test
  @DisplayName("본문 수정은 이전 revision을 보존한다")
  void editedTextCreatesSecondRevisionAndKeepsFirst() throws Exception {
    s.givenPost();
    // when
    s.save(s.found("Edited"));
    // then
    assertThat(s.postRevisionTexts()).containsExactly("Body", "Edited");
  }

  @Test
  @DisplayName("새 댓글은 글 revision을 늘리지 않는다")
  void newCommentCreatesCommentRevision() throws Exception {
    s.givenPost();
    // when
    boolean changed = s.save(s.found("Body", "raw", List.of(s.comment("Reply")), true));
    // then
    assertThat(changed).isTrue();
    assertThat(s.revisionCounts()).isEqualTo(Map.of("42", 1));
  }

  @Test
  @DisplayName("완료 목록에서 빠진 댓글만 삭제 상태가 된다")
  void missingCommentIsDeletedOnlyWhenListComplete() throws Exception {
    s.save(s.found("Body", "raw", List.of(s.comment("Reply")), true));
    // when
    s.save(s.found("Body", "raw", List.of(), false));
    // then
    assertThat(s.commentStatus()).isEqualTo("NORMAL");
  }

  @Test
  @DisplayName("완전한 댓글 목록의 누락은 삭제다")
  void completeMissingCommentIsDeleted() throws Exception {
    s.save(s.found("Body", "raw", List.of(s.comment("Reply")), true));
    // when
    s.save(s.found("Body"));
    // then
    assertThat(s.commentStatus()).isEqualTo("DELETED");
  }

  @Test
  @DisplayName("게시판 이동은 동일 글과 이동 이력을 유지한다")
  void moveBetweenBoardsKeepsSamePostAndRecordsHistory() throws Exception {
    s.givenPost();
    // when
    s.recordSummaryInBoard("b");
    // then
    assertThat(s.store.post(ArchiveScenario.SITE, "42"))
        .get()
        .extracting(PostState::boardId)
        .isEqualTo("b");
    assertThat(s.boardMoveCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("확인 필요 글을 목록에서 보면 정상으로 돌아온다")
  void needsCheckReturnsToNormalWhenSeenInList() {
    s.givenPost();
    s.store.markStatus(ArchiveScenario.SITE, "42", PostStatus.NEEDS_CHECK);
    // when
    s.recordSummaryInBoard("a");
    // then
    assertThat(s.store.post(ArchiveScenario.SITE, "42"))
        .get()
        .extracting(PostState::status)
        .isEqualTo(PostStatus.NORMAL);
  }

  @Test
  @DisplayName("앞쪽과 뒤쪽 커서를 영속화한다")
  void cursorsRoundTrip() {
    var key = new PostKey(ArchiveScenario.CREATED, "42");
    var cursors =
        new BoardCursors(Optional.of(key), Optional.of(new BackfillCursor("page2", key)), false);
    // when
    s.store.saveCursors(ArchiveScenario.SITE, "a", cursors);
    // then
    assertThat(s.store.cursors(ArchiveScenario.SITE, "a")).isEqualTo(cursors);
  }

  @Test
  @DisplayName("WAL 모드로 보관소를 연다")
  void walModeIsEnabled() throws Exception {
    // then
    assertThat(s.journalMode()).isEqualTo("wal");
  }

  @Test
  @DisplayName("실행 결과와 HTTP 상태와 허락 근거를 보관한다")
  void runIsRecordedWithStatusCountsAndPermission() throws Exception {
    // when
    s.completeLoggedRun();
    // then
    assertThat(s.lastRun())
        .extracting(
            r -> r.finished(), r -> r.requests(), r -> r.newPosts(), r -> r.permissionEvidence())
        .containsExactly(true, 2, 2, Optional.of("local permission record"));
    assertThat(s.store.completedRuns(ArchiveScenario.SITE)).isEqualTo(1);
  }

  @Test
  @DisplayName("이전 버전 보관소를 열면 원본을 보존하며 훑기 폐기 사유를 쓸 수 있다 (§5)")
  void migratesVersionOneWithoutLosingPosts() throws Exception {
    // given
    s.givenPost();
    s.reopenArchiveAt(1);
    long sweep = s.store.startSweep("example-board", s.clock.instant()).id();
    // when
    s.store.discardSweep(sweep, "BOARD_SET_CHANGED");
    // then
    assertThat(s.postRevisionTexts()).containsExactly("Body");
    assertThat(s.sweepDiscardReason()).isEqualTo("BOARD_SET_CHANGED");
  }

  @Test
  @DisplayName("V4 보관소를 열면 삭제 근거를 기록하고 첫 확인 시각을 돌려준다")
  void migratesVersionFourWithGoneEvidence() throws Exception {
    // given
    s.givenPost();
    s.reopenArchiveAt(4);
    Instant firstGone = s.clock.instant();
    s.store.recordGone(ArchiveScenario.SITE, "42", 1, firstGone);
    s.clock.advance(LATER);
    // when
    var first = s.store.recordGone(ArchiveScenario.SITE, "42", 2, s.clock.instant());
    // then
    assertThat(first).contains(firstGone);
  }

  @Test
  @DisplayName("V5 보관소의 시각 없는 삭제 근거는 확인 시각 없이 다시 센다")
  void restartsVersionFiveGoneEvidenceWithoutTime() throws Exception {
    // given
    s.givenPost();
    s.givenVersionFiveGoneEvidence(1);
    s.clock.advance(LATER);
    // when
    var first = s.store.recordGone(ArchiveScenario.SITE, "42", 2, s.clock.instant());
    // then
    assertThat(first).isEmpty();
  }

  @Test
  @DisplayName("V5 근거를 다시 센 다음 훑기는 다시 센 시각을 돌려준다")
  void nextSweepReturnsRestartedGoneTime() throws Exception {
    // given
    s.givenPost();
    s.givenVersionFiveGoneEvidence(1);
    s.clock.advance(LATER);
    Instant restarted = s.clock.instant();
    s.store.recordGone(ArchiveScenario.SITE, "42", 2, restarted);
    s.clock.advance(LATER);
    // when
    var first = s.store.recordGone(ArchiveScenario.SITE, "42", 3, s.clock.instant());
    // then
    assertThat(first).contains(restarted);
  }

  @Test
  @DisplayName("이전 JSON 체크포인트는 중단된 작업 없이 그대로 재개한다 (§5)")
  void readsCheckpointWithoutContinuation() throws Exception {
    // given
    s.givenLegacyCheckpoint();
    // when
    var checkpoint = s.store.crawlCheckpoint("example-board", "a").orElseThrow();
    // then
    assertThat(checkpoint)
        .isEqualTo(
            new CrawlCheckpoint(
                CrawlCheckpoint.Phase.RECHECK, "3", Optional.empty(), Optional.empty()));
  }
}
