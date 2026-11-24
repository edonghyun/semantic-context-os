package io.scos.boardarchive.store;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.PostKey;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@DisplayName("수집 단계는 앞쪽·재확인·과거 재개의 순서를 보존한다 (§4)")
class CrawlCheckpointTest {
  private static final PostKey FRONT = new PostKey(Instant.parse("2026-11-17T00:09:00Z"), "9");
  private static final PostKey BACK = new PostKey(Instant.parse("2026-11-17T00:03:00Z"), "3");
  private static final CrawlCheckpoint HISTORY =
      new CrawlCheckpoint(CrawlCheckpoint.Phase.BACKFILL, "6", Optional.of(BACK), Optional.empty());
  private static final CrawlCheckpoint RECHECK =
      new CrawlCheckpoint(CrawlCheckpoint.Phase.RECHECK, "3", Optional.of(BACK), Optional.empty());
  private static final BoardCursors INCOMPLETE =
      new BoardCursors(Optional.of(FRONT), Optional.of(new BackfillCursor("6", BACK)), false);
  private static final BoardCursors COMPLETE =
      new BoardCursors(Optional.of(FRONT), Optional.empty(), true);

  private static final CrawlCheckpoint FRONT_SCAN =
      new CrawlCheckpoint(CrawlCheckpoint.Phase.FRONT, "", Optional.empty(), Optional.of(FRONT));
  private static final CrawlCheckpoint RECHECK_SCAN =
      new CrawlCheckpoint(CrawlCheckpoint.Phase.RECHECK, "", Optional.empty(), Optional.empty());
  private static final CrawlCheckpoint DONE_SCAN =
      new CrawlCheckpoint(CrawlCheckpoint.Phase.DONE, "", Optional.empty(), Optional.empty());

  @ParameterizedTest
  @MethodSource("transitions")
  @DisplayName("단계가 끝나면 전이 표의 다음 위치로 이동한다")
  void followsPhaseTable(CrawlCheckpoint current, BoardCursors cursors, CrawlCheckpoint expected) {
    // when
    var next = current.nextPhase(cursors);
    // then
    assertThat(next).isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("suspendedScans")
  @DisplayName("새 실행은 중단 위치를 보존하고 앞쪽을 먼저 읽는다")
  void startsWithFront(CrawlCheckpoint saved) {
    // when
    var first = saved.prioritizeFront(Optional.of(FRONT));
    // then
    assertThat(first)
        .isEqualTo(
            new CrawlCheckpoint(
                CrawlCheckpoint.Phase.FRONT,
                "",
                Optional.empty(),
                Optional.of(FRONT),
                Optional.of(saved)));
  }

  static Stream<CrawlCheckpoint> suspendedScans() {
    return Stream.of(HISTORY, RECHECK, PENDING_SCAN);
  }

  private static final CrawlCheckpoint PENDING_SCAN =
      new CrawlCheckpoint(CrawlCheckpoint.Phase.PENDING, "", Optional.empty(), Optional.empty());
  private static final CrawlCheckpoint FRONT_HISTORY =
      new CrawlCheckpoint(
          CrawlCheckpoint.Phase.FRONT,
          "",
          Optional.empty(),
          Optional.of(FRONT),
          Optional.of(HISTORY));
  private static final CrawlCheckpoint RECHECK_HISTORY =
      new CrawlCheckpoint(
          CrawlCheckpoint.Phase.RECHECK,
          "",
          Optional.empty(),
          Optional.empty(),
          Optional.of(HISTORY));
  private static final CrawlCheckpoint PENDING_HISTORY =
      new CrawlCheckpoint(
          CrawlCheckpoint.Phase.PENDING,
          "",
          Optional.empty(),
          Optional.empty(),
          Optional.of(HISTORY));

  static Stream<Arguments> transitions() {
    var frontRecheck =
        new CrawlCheckpoint(
            CrawlCheckpoint.Phase.FRONT,
            "",
            Optional.empty(),
            Optional.of(FRONT),
            Optional.of(RECHECK));
    var frontPending =
        new CrawlCheckpoint(
            CrawlCheckpoint.Phase.FRONT,
            "",
            Optional.empty(),
            Optional.of(FRONT),
            Optional.of(PENDING_SCAN));
    return Stream.of(
        Arguments.of(FRONT_SCAN, INCOMPLETE, RECHECK_SCAN),
        Arguments.of(FRONT_HISTORY, INCOMPLETE, RECHECK_HISTORY),
        Arguments.of(RECHECK_HISTORY, INCOMPLETE, PENDING_HISTORY),
        Arguments.of(PENDING_HISTORY, INCOMPLETE, HISTORY),
        Arguments.of(frontRecheck, INCOMPLETE, RECHECK),
        Arguments.of(frontPending, INCOMPLETE, PENDING_SCAN),
        Arguments.of(RECHECK_SCAN, INCOMPLETE, PENDING_SCAN),
        Arguments.of(RECHECK_SCAN, COMPLETE, PENDING_SCAN),
        Arguments.of(PENDING_SCAN, INCOMPLETE, HISTORY),
        Arguments.of(PENDING_SCAN, COMPLETE, DONE_SCAN),
        Arguments.of(HISTORY, INCOMPLETE, DONE_SCAN),
        Arguments.of(DONE_SCAN, COMPLETE, DONE_SCAN));
  }
}
