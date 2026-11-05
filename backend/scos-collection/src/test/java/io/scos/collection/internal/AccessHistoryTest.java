package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근 이력은 마감된 시도만 미완료 추세로 센다(O-3)")
class AccessHistoryTest {
  private static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  private static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");

  private CollectionRun finished(AccessResult result) {
    return CollectionRun.started(new RunId("run-" + result.name()), SOURCE, T0)
        .finished(
            RunOutcome.succeeded(0),
            CollectionCoverage.INCREMENTAL,
            T0.plusSeconds(1),
            0,
            result,
            Optional.of(T0.plusSeconds(1)));
  }

  @Test
  @DisplayName("마감된 최신 run은 실행 중으로 보고하지 않는다")
  void completedRunIsNotRunning() {
    var history = AccessHistory.of(List.of(finished(AccessResult.COMPLETE)));
    // then
    assertThat(history.running()).isFalse();
  }

  @Test
  @DisplayName("본문 성공도 접근 미완료 streak를 초기화하지 않는다")
  void bodySuccessDoesNotResetAccessStreak() {
    var history =
        AccessHistory.of(
            List.of(
                finished(AccessResult.INCOMPLETE),
                finished(AccessResult.CLAIM_LOST),
                finished(AccessResult.COMPLETE),
                finished(AccessResult.INCOMPLETE)));
    // then
    assertThat(history.consecutiveIncomplete()).isEqualTo(2);
  }

  @Test
  @DisplayName("실행 중 run은 미완료 수에 포함하지 않는다")
  void runningIsNotIncomplete() {
    var history =
        AccessHistory.of(List.of(CollectionRun.started(new RunId("running"), SOURCE, T0)));
    // then
    assertThat(history.running()).isTrue();
    assertThat(history.consecutiveIncomplete()).isZero();
  }

  @Test
  @DisplayName("접근 미시도와 미지원 run은 streak를 늘리지 않는다")
  void skippedAndUnsupportedDoNotCount() {
    var history =
        AccessHistory.of(
            List.of(finished(AccessResult.SKIPPED), finished(AccessResult.UNSUPPORTED)));
    // then
    assertThat(history.consecutiveIncomplete()).isZero();
  }

  @Test
  @DisplayName("완료 관측이 없는 이력은 마지막 COMPLETE 시각을 만들지 않는다")
  void missingCompleteHasNoInventedTimestamp() {
    var history = AccessHistory.of(List.of(finished(AccessResult.INCOMPLETE)));
    // then
    assertThat(history.lastCompleteAt()).isEmpty();
  }

  @Test
  @DisplayName("마지막 COMPLETE 시각은 접근 종료 시각이다")
  void lastCompleteUsesAccessFinishTime() {
    var history = AccessHistory.of(List.of(finished(AccessResult.COMPLETE)));
    // then
    assertThat(history.lastCompleteAt()).contains(Instant.parse("2026-10-14T00:00:01Z"));
  }

  @Test
  @DisplayName("실행 이력이 비면 결과와 시각과 파일 목록이 없다")
  void emptyHistoryHasNoResult() {
    var history = AccessHistory.of(List.of());
    // then
    assertThat(history.result()).isEmpty();
    assertThat(history.finishedAt()).isEmpty();
    assertThat(history.unreadableFiles()).isEmpty();
  }

  @Test
  @DisplayName("실행 중 run의 부분 값 대신 마지막 접근 마감 run의 독자 거부 수를 유지한다")
  void runningRunKeepsLastFinishedUnreadableFiles() {
    var running =
        CollectionRun.started(new RunId("running"), SOURCE, T0.plusSeconds(2))
            .withUnreadableAcls(Set.of("file:partial"));
    var history =
        AccessHistory.of(
            List.of(
                running,
                finished(AccessResult.COMPLETE).withUnreadableAcls(Set.of("file:F1", "file:F2"))));
    // then
    assertThat(history.unreadableFiles()).containsExactlyInAnyOrder("file:F1", "file:F2");
  }

  @Test
  @DisplayName("접근 미시도 run도 마지막 접근 마감의 독자 거부 수를 지우지 않는다")
  void unattemptedRunKeepsLastFinishedUnreadableFiles() {
    var unattempted = CollectionRun.started(new RunId("body-failure"), SOURCE, T0.plusSeconds(2));
    var history =
        AccessHistory.of(
            List.of(
                unattempted,
                finished(AccessResult.INCOMPLETE).withUnreadableAcls(Set.of("file:F1"))));
    // then
    assertThat(history.unreadableFiles()).containsExactly("file:F1");
  }

  @Test
  @DisplayName("최근 run의 확정 독자 거부 파일을 조회한다")
  void latestUnreadableFilesArePreserved() {
    var history =
        AccessHistory.of(
            List.of(finished(AccessResult.COMPLETE).withUnreadableAcls(Set.of("file:F1"))));
    // then
    assertThat(history.unreadableFiles()).containsExactly("file:F1");
  }
}
