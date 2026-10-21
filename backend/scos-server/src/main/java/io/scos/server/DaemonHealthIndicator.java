package io.scos.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * `/actuator/health`의 `daemon` 구성요소(ADR-0020 §3): 루프가 살아 있고 마지막 진행({@link
 * CollectionDaemon#lastProgressAt} — 루프와 수집 중인 시도마다의 진행 중 가장 이른 것)이 stallAfter 안이면 UP. 루프가 멈췄거나
 * 죽었으면 DOWN, 진행이 stallAfter보다 오래됐으면 DOWN(stalled). 세부: running·stalled·cycles·lastCycleAt·
 * lastCycleError·fatalError(예외 종류만 — 소스 id·본문 없음). 데몬 객체가 아니라 읽기 함수만 받아 순수하게 둔다. 데몬에서 그 상태를 읽는 매핑은
 * {@link DaemonState#of}(제품 조립과 테스트가 같이 쓴다)다.
 */
public final class DaemonHealthIndicator implements HealthIndicator {
  /** health가 읽는 데몬 상태(한 시점). */
  public record DaemonState(
      boolean loopAlive,
      long cycles,
      Optional<Instant> lastCycleAt,
      Optional<Instant> lastProgressAt,
      Optional<String> lastCycleError,
      Optional<String> fatalError) {

    /** 데몬의 지금 상태. lastCycle은 관측자가 본 마지막 사이클 결과(없으면 사이클 오류도 없다). 예외는 종류 이름만 남긴다. */
    public static DaemonState of(
        CollectionDaemon daemon, Optional<CollectionDaemon.CycleOutcome> lastCycle) {
      return new DaemonState(
          daemon.loopAlive(),
          daemon.cycles(),
          daemon.lastCycleAt(),
          daemon.lastProgressAt(),
          lastCycle.flatMap(CollectionDaemon.CycleOutcome::error).map(DaemonState::kind),
          daemon.fatalError().map(DaemonState::kind));
    }

    private static String kind(Throwable error) {
      return error.getClass().getSimpleName();
    }
  }

  private final Supplier<DaemonState> state;
  private final Clock clock;
  private final Duration stallAfter;

  public DaemonHealthIndicator(Supplier<DaemonState> state, Clock clock, Duration stallAfter) {
    this.state = state;
    this.clock = clock;
    this.stallAfter = stallAfter;
  }

  @Override
  public Health health() {
    DaemonState s = state.get();
    boolean stalled =
        s.lastProgressAt().map(t -> t.plus(stallAfter).isBefore(clock.instant())).orElse(false);
    Health.Builder b = s.loopAlive() && !stalled ? Health.up() : Health.down();
    b.withDetail("running", s.loopAlive())
        .withDetail("stalled", stalled)
        .withDetail("cycles", s.cycles());
    s.lastCycleAt().ifPresent(t -> b.withDetail("lastCycleAt", t.toString()));
    s.lastCycleError().ifPresent(e -> b.withDetail("lastCycleError", e));
    s.fatalError().ifPresent(e -> b.withDetail("fatalError", e));
    return b.build();
  }
}
