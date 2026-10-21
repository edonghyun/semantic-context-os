package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** health `daemon` 구성요소(ADR-0020 §3): 살아 있고 정체되지 않았으면 UP, 루프가 멈췄거나 죽었거나 정체됐으면 DOWN. */
@DisplayName("health daemon 구성요소는 루프 생존과 정체로 UP·DOWN을 정한다(ADR-0020 §3)")
class DaemonHealthIndicatorTest {
  static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
  static final Duration STALL_AFTER = Duration.ofMinutes(30);
  static final Instant RECENT = NOW.minus(STALL_AFTER).plusSeconds(1);
  static final Instant TOO_OLD = NOW.minus(STALL_AFTER).minusSeconds(1);

  /** 상태 대역. 기본은 루프가 죽었고 기록이 없는 상태다 — 테스트가 필요한 필드만 이름으로 바꾼다. */
  static final class State {
    boolean loopAlive;
    long cycles;
    Optional<Instant> lastCycleAt = Optional.empty();
    Optional<Instant> lastProgressAt = Optional.empty();
    Optional<String> lastCycleError = Optional.empty();
    Optional<String> fatalError = Optional.empty();

    State running() {
      loopAlive = true;
      return this;
    }

    State cycles(long n) {
      cycles = n;
      return this;
    }

    State lastCycleAt(Instant at) {
      lastCycleAt = Optional.of(at);
      return this;
    }

    State lastProgressAt(Instant at) {
      lastProgressAt = Optional.of(at);
      return this;
    }

    State lastCycleError(String kind) {
      lastCycleError = Optional.of(kind);
      return this;
    }

    State fatalError(String kind) {
      fatalError = Optional.of(kind);
      return this;
    }

    DaemonHealthIndicator.DaemonState build() {
      return new DaemonHealthIndicator.DaemonState(
          loopAlive, cycles, lastCycleAt, lastProgressAt, lastCycleError, fatalError);
    }
  }

  static State state() {
    return new State();
  }

  static Health healthOf(State state) {
    DaemonHealthIndicator.DaemonState s = state.build();
    return new DaemonHealthIndicator(() -> s, Clock.fixed(NOW, ZoneOffset.UTC), STALL_AFTER)
        .health();
  }

  @Nested
  @DisplayName("실행 중이고 정체 기준 안에 사이클과 진행을 남겼으면")
  class RunningRecently {
    final State running = state().running().cycles(3).lastCycleAt(RECENT).lastProgressAt(RECENT);

    @Test
    @DisplayName("UP이다")
    void upWhileRunning() {
      // when
      Health h = healthOf(running);

      // then
      assertThat(h.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("세부에는 오류 항목 없이 실행 상태만 실린다")
    void upDetails() {
      // when
      Health h = healthOf(running);

      // then
      assertThat(h.getDetails())
          .containsEntry("running", true)
          .containsEntry("stalled", false)
          .containsEntry("cycles", 3L)
          .containsEntry("lastCycleAt", RECENT.toString())
          .doesNotContainKeys("lastCycleError", "fatalError");
    }
  }

  @Test
  @DisplayName("루프가 살아 있지 않으면(정지·사망) DOWN이다")
  void downWhenLoopNotAlive() {
    // when
    Health h = healthOf(state());

    // then
    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("마지막 진행이 정체 기준(30분)보다 오래되면 루프가 살아 있어도 DOWN(stalled)이다")
  void downWhenStalled() {
    // given: 사이클은 최근에 끝났지만 진행은 정체 기준보다 오래됐다
    State stalled = state().running().cycles(3).lastCycleAt(RECENT).lastProgressAt(TOO_OLD);

    // when
    Health h = healthOf(stalled);

    // then
    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    assertThat(h.getDetails()).containsEntry("stalled", true);
  }

  @Test
  @DisplayName("마지막 진행이 정확히 정체 기준(30분) 전이면 아직 정체가 아니라 UP이다")
  void upExactlyAtStallBoundary() {
    // given: 경계는 "기준보다 오래되면" — 정확히 30분은 넘지 않았다
    State atBoundary =
        state()
            .running()
            .cycles(3)
            .lastCycleAt(RECENT)
            .lastProgressAt(Instant.parse("2026-10-14T11:30:00Z"));

    // when
    Health h = healthOf(atBoundary);

    // then
    assertThat(h.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("기동 직후처럼 실행 중이지만 사이클·진행 기록이 없으면 UP이다")
  void upBeforeFirstRecord() {
    // given
    State justStarted = state().running();

    // when
    Health h = healthOf(justStarted);

    // then
    assertThat(h.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("루프를 죽인 치명 오류는 예외 종류 이름만 세부에 실린다")
  void fatalErrorIsReportedByKind() {
    // given
    State died =
        state().cycles(1).lastCycleAt(RECENT).lastProgressAt(RECENT).fatalError("LinkageError");

    // when
    Health h = healthOf(died);

    // then
    assertThat(h.getDetails()).containsEntry("fatalError", "LinkageError");
  }

  @Nested
  @DisplayName("실행 중이고 마지막 사이클이 데몬 수준 오류로 끝났으면")
  class LastCycleFailed {
    final State lastCycleFailed =
        state()
            .running()
            .cycles(1)
            .lastCycleAt(RECENT)
            .lastProgressAt(RECENT)
            .lastCycleError("IllegalStateException");

    @Test
    @DisplayName("오류는 예외 종류 이름으로 실린다")
    void lastCycleErrorIsReportedByKind() {
      // when
      Health h = healthOf(lastCycleFailed);

      // then
      assertThat(h.getDetails()).containsEntry("lastCycleError", "IllegalStateException");
    }

    @Test
    @DisplayName("UP이다")
    void lastCycleErrorKeepsUp() {
      // when
      Health h = healthOf(lastCycleFailed);

      // then
      assertThat(h.getStatus()).isEqualTo(Status.UP);
    }
  }
}
