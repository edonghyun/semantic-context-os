package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.CountedWorkers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * succeeded run에 errorKind가 붙는 유일한 경우(스냅샷 축소 보호, ADR-0028)를 감독이 실패로 읽지 않는다: 재시도 지연이 아니라 성공 주기를
 * 기다리고, 최근 실패 수를 0으로 되돌리고, 사이클 시도는 ok다.
 */
@DisplayName("감독과 축소 보호 run — snapshot_shrink_guarded는 실패가 아니다(ADR-0028)")
class SupervisorShrinkGuardTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("tiro-main");
  static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");
  static final Instant STARTED = T0.minusSeconds(10);
  static final Duration INTERVAL = Duration.ofMinutes(15);
  static final Duration RETRY_BASE = Duration.ofSeconds(30);
  static final Duration RETRY_MAX = Duration.ofMinutes(5);

  /** 사이클 대기 상한 — hang 방지용이며 성공 근거가 아니다. */
  static final Duration CYCLE_LIMIT = Duration.ofSeconds(10);

  static final Optional<String> GUARDED = Optional.of(SnapshotShrinkGuard.ERROR_KIND);

  final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  final MemoryRunStore runs = new MemoryRunStore();
  final CountedWorkers workers = CountedWorkers.of(1);

  @BeforeEach
  void givenRegisteredSource() {
    sources.save(SourceInstances.noop(SOURCE, T0));
  }

  @AfterEach
  void stopWorkers() {
    workers.shutdownNow();
  }

  /** run 하나가 started에 시작해 1초 뒤 끝났다. */
  void givenFinishedRun(String id, Instant started, RunStatus status, Optional<String> kind) {
    runs.start(new RunId(id), SOURCE, started);
    runs.finish(
        new RunId(id),
        status,
        CollectionCoverage.COMPLETE_SNAPSHOT,
        started.plusSeconds(1),
        status.isSuccess() ? 0 : 1,
        kind,
        Optional.empty(),
        0);
  }

  SourceSupervisor supervisor(SourceSupervisor.Collector collector) {
    return new SourceSupervisor(
        sources,
        runs,
        new MemoryWorkClaimPort(),
        collector,
        new SourceSupervisor.Options(INTERVAL, RETRY_BASE, RETRY_MAX, 1, Optional.empty()),
        Clock.fixed(T0, ZoneOffset.UTC),
        workers);
  }

  /** 감독 한 사이클 — 수집하면 보호된 run이 나온다. */
  SourceSupervisor.Cycle runCycleCollectingGuardedRun() {
    return supervisor((source, mode, cancel) -> guardedReport())
        .runDueSources(CancellationToken.none(), CYCLE_LIMIT)
        .orElseThrow(() -> new AssertionError("사이클이 " + CYCLE_LIMIT + " 안에 끝나지 않았다"));
  }

  /** 보호 사유가 붙은 성공 보고(오류 0). */
  static CollectionRuntime.Report guardedReport() {
    return new CollectionRuntime.Report(
        new RunId("run-guarded"),
        SOURCE,
        RunStatus.SUCCEEDED,
        1,
        0,
        0,
        5,
        0,
        0,
        GUARDED,
        Optional.of("complete snapshot reconcile skipped"));
  }

  @Nested
  @DisplayName("마지막 run이 보호된 succeeded면")
  class LastRunGuarded {
    @BeforeEach
    void givenFailureThenGuardedRun() {
      givenFinishedRun("run-1", STARTED.minusSeconds(60), RunStatus.FAILED, Optional.of("Error"));
      givenFinishedRun("run-2", STARTED, RunStatus.SUCCEEDED, GUARDED);
    }

    @Test
    @DisplayName("다음 수집은 재시도 지연이 아니라 성공 주기 뒤다")
    void waitsForInterval() {
      // when
      SourceSupervisor.Cycle cycle = runCycleCollectingGuardedRun();

      // then
      assertThat(cycle.deferred()).containsEntry(SOURCE, STARTED.plus(INTERVAL));
    }

    @Test
    @DisplayName("최근 실패 수는 0이다")
    void resetsFailureCount() {
      // when
      int failures = runs.recentFailureCount(SOURCE);

      // then
      assertThat(failures).isZero();
    }
  }

  @Nested
  @DisplayName("이번 시도가 보호된 run으로 끝나면")
  class AttemptGuarded {
    @Test
    @DisplayName("시도는 실패로 세지 않는다")
    void attemptIsNotFailed() {
      // when
      SourceSupervisor.Cycle cycle = runCycleCollectingGuardedRun();

      // then
      assertThat(cycle.attempts()).singleElement().matches(a -> !a.failed(), "실패가 아닌 시도");
    }
  }
}
