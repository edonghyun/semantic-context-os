package io.scos.server;

import static io.scos.server.DaemonObserver.ATTEMPTS;
import static io.scos.server.DaemonObserver.CYCLE;
import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.Reports;
import io.scos.collection.internal.SourceSupervisor.Attempt;
import io.scos.collection.internal.SourceSupervisor.AttemptOutcome;
import io.scos.collection.internal.SourceSupervisor.Cycle;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사이클 결과 → 마지막 상태(health)와 사이클 지표. 데몬 루프 없이 결과를 직접 기록한다. */
@DisplayName("데몬 관측자는 사이클 결과를 마지막 상태와 사이클·시도 지표로 남긴다")
class DaemonObserverTest {
  static final Instant STARTED = Instant.parse("2026-10-14T00:00:00Z");
  static final Instant FINISHED = STARTED.plusSeconds(3);

  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final DaemonObserver observer = new DaemonObserver(registry);

  @Test
  @DisplayName("기록한 사이클 결과를 마지막 사이클로 기억한다")
  void remembersLastCycle() {
    // given
    CollectionDaemon.CycleOutcome outcome = cycleWith(succeeded("a"));

    // when
    observer.record(outcome);

    // then
    assertThat(observer.last()).containsSame(outcome);
  }

  @Test
  @DisplayName("수집이 있는 사이클마다 사이클 타이머를 한 번 기록한다")
  void timesEachCycle() {
    // when
    observer.record(cycleWith(succeeded("a")));

    // then
    assertThatMetrics(registry).hasTimerCount(CYCLE, 1);
  }

  @Test
  @DisplayName("성공한 시도를 ok로 센다")
  void countsSuccesses() {
    // when
    observer.record(cycleWith(succeeded("a")));

    // then
    assertThatMetrics(registry).hasCounter(ATTEMPTS, "outcome", "ok", 1);
  }

  @Test
  @DisplayName("성공한 시도만 있으면 failed 카운터를 만들지 않는다")
  void countsOnlySuccesses() {
    // when
    observer.record(cycleWith(succeeded("a")));

    // then
    assertThatMetrics(registry).hasNoCounter(ATTEMPTS, "outcome", "failed");
  }

  @Test
  @DisplayName("실패한 시도를 failed로 센다")
  void countsFailures() {
    // when
    observer.record(cycleWith(failed("a"), failed("b")));

    // then
    assertThatMetrics(registry).hasCounter(ATTEMPTS, "outcome", "failed", 2);
  }

  @Test
  @DisplayName("시도가 전부 실패하면 ok 카운터를 만들지 않는다")
  void countsOnlyFailures() {
    // when
    observer.record(cycleWith(failed("a"), failed("b")));

    // then
    assertThatMetrics(registry).hasNoCounter(ATTEMPTS, "outcome", "ok");
  }

  @Test
  @DisplayName("사이클마다 성공과 실패를 각각 더한다")
  void accumulatesAcrossCycles() {
    // given
    observer.record(cycleWith(failed("a")));

    // when
    observer.record(cycleWith(succeeded("a")));

    // then
    assertThatMetrics(registry)
        .hasCounter(ATTEMPTS, "outcome", "failed", 1)
        .hasCounter(ATTEMPTS, "outcome", "ok", 1);
  }

  @Test
  @DisplayName("수집 없이 오류로 끝난 사이클은 타이머·시도 지표를 남기지 않는다")
  void failedCycleLeavesNoMetrics() {
    // when
    observer.record(failedCycle());

    // then
    assertThatMetrics(registry).hasNoMeter(CYCLE).hasNoMeter(ATTEMPTS);
  }

  // ---- 데이터 ----

  static CollectionDaemon.CycleOutcome cycleWith(Attempt... attempts) {
    Cycle cycle = new Cycle(STARTED, FINISHED, List.of(attempts), Map.of());
    return new CollectionDaemon.CycleOutcome(
        FINISHED, Optional.of(cycle), List.of(), Optional.empty());
  }

  static CollectionDaemon.CycleOutcome failedCycle() {
    return new CollectionDaemon.CycleOutcome(
        FINISHED, Optional.empty(), List.of(), Optional.of(new IllegalStateException("down")));
  }

  static Attempt succeeded(String source) {
    SourceInstanceId id = new SourceInstanceId(source);
    return new Attempt(
        id, CollectionMode.INCREMENTAL, new AttemptOutcome.Collected(Reports.succeeded(id)));
  }

  static Attempt failed(String source) {
    return new Attempt(
        new SourceInstanceId(source),
        CollectionMode.INCREMENTAL,
        new AttemptOutcome.Failed(new IllegalStateException("boom")));
  }
}
