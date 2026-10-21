package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
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
import org.junit.jupiter.api.Test;

/**
 * 소스별 감독 주기(ADR-0022 §3, TS `--interval-seconds-for`·`snapshotIntervalMsBySource`)의 due 계약. 주기는 등록
 * 저장소를 거쳐 감독에 닿으므로, 같은 사례를 메모리 포트와 PostgreSQL 포트 양쪽으로 돌린다.
 */
@DisplayName("감독은 소스별 수집·스냅샷 주기로 due를 정한다(ADR-0022 §3)")
public abstract class SupervisorScheduleContract {
  protected static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final Duration DAEMON_INTERVAL = Duration.ofMinutes(15);
  static final Duration RETRY_BASE = Duration.ofSeconds(30);
  static final Duration RETRY_MAX = Duration.ofMinutes(5);

  /** 운영 Notion·Jira·Drive 주기(TS `--interval-seconds-for …=21600`). */
  static final Duration SIX_HOURS = Duration.ofHours(6);

  static final Duration HOURLY = Duration.ofHours(1);
  static final Duration DAILY = Duration.ofDays(1);

  /** 마지막 run 시작: 재시도 지연(30s)도 아직 지나지 않은 시각. */
  static final Instant STARTED = T0.minusSeconds(10);

  /** 마지막 complete snapshot: 1시간 간격은 지났고 1일 간격은 아직이다. */
  static final Instant SNAPSHOT_COMPLETED = T0.minus(Duration.ofHours(2));

  static final SourceInstanceId SOURCE = new SourceInstanceId("notion-main");

  /** 사이클 대기 상한 — hang 방지용이며 성공 근거가 아니다. */
  static final Duration CYCLE_LIMIT = Duration.ofSeconds(10);

  /** 감독이 읽는 포트 묶음(같은 구현 계열). */
  public record Ports(SourceInstanceStore sources, RunStore runs, WorkClaimPort claims) {}

  protected abstract Ports newPorts();

  private Ports ports;
  private final CountedWorkers workers = CountedWorkers.of(1);

  @BeforeEach
  void setUpContract() {
    ports = newPorts();
  }

  @AfterEach
  void tearDownContract() {
    workers.shutdownNow();
  }

  // ---- 준비 ----

  void givenSource(SourceSchedule schedule) {
    ports.sources().save(SourceInstances.scheduled(SOURCE, schedule, T0));
  }

  void givenRun(RunStatus status) {
    RunId run = new RunId("seed-" + status.wire());
    ports.runs().start(run, SOURCE, STARTED);
    ports
        .runs()
        .finish(
            run,
            status,
            CollectionCoverage.INCREMENTAL,
            STARTED.plusSeconds(1),
            status == RunStatus.SUCCEEDED ? 0 : 1,
            Optional.empty(),
            Optional.empty(),
            0);
  }

  void givenSnapshotCompleted() {
    ports.runs().recordSnapshotCompletion(SOURCE, SNAPSHOT_COMPLETED, 0);
  }

  // ---- 실행 ----

  SourceSupervisor.Cycle runCycle(Optional<Duration> daemonSnapshotInterval) {
    SourceSupervisor supervisor =
        new SourceSupervisor(
            ports.sources(),
            ports.runs(),
            ports.claims(),
            (source, mode, cancel) -> Reports.succeeded(source),
            new SourceSupervisor.Options(
                DAEMON_INTERVAL, RETRY_BASE, RETRY_MAX, 1, daemonSnapshotInterval),
            Clock.fixed(T0, ZoneOffset.UTC),
            workers);
    return supervisor
        .runDueSources(CancellationToken.none(), CYCLE_LIMIT)
        .orElseThrow(() -> new AssertionError("사이클이 " + CYCLE_LIMIT + " 안에 끝나지 않았다"));
  }

  SourceSupervisor.Cycle runCycle() {
    return runCycle(Optional.empty());
  }

  CollectionMode attemptedMode(SourceSupervisor.Cycle cycle) {
    return cycle.attempts().getFirst().mode();
  }

  // ---- 계약 ----

  @Test
  @DisplayName("소스별 수집 주기가 있으면 성공 뒤 그 주기까지 미룬다")
  void perSourceIntervalDefersSuccess() {
    // given
    givenSource(new SourceSchedule(Optional.of(SIX_HOURS), Optional.empty()));
    givenRun(RunStatus.SUCCEEDED);

    // when
    SourceSupervisor.Cycle cycle = runCycle();

    // then
    assertThat(cycle.deferred()).containsEntry(SOURCE, STARTED.plus(SIX_HOURS));
  }

  @Test
  @DisplayName("소스별 수집 주기가 없으면 데몬 기본 주기까지 미룬다")
  void daemonIntervalWithoutPerSourceValue() {
    // given
    givenSource(SourceSchedule.DAEMON_DEFAULTS);
    givenRun(RunStatus.SUCCEEDED);

    // when
    SourceSupervisor.Cycle cycle = runCycle();

    // then
    assertThat(cycle.deferred()).containsEntry(SOURCE, STARTED.plus(DAEMON_INTERVAL));
  }

  @Test
  @DisplayName("소스별 수집 주기는 실패 뒤 재시도 지연을 바꾸지 않는다")
  void perSourceIntervalKeepsRetryDelay() {
    // given
    givenSource(new SourceSchedule(Optional.of(SIX_HOURS), Optional.empty()));
    givenRun(RunStatus.FAILED);

    // when
    SourceSupervisor.Cycle cycle = runCycle();

    // then
    assertThat(cycle.deferred()).containsEntry(SOURCE, STARTED.plus(RETRY_BASE));
  }

  @Test
  @DisplayName("소스별 스냅샷 주기는 데몬 스냅샷 설정이 없어도 스냅샷을 정한다")
  void perSourceSnapshotWithoutDaemonSetting() {
    // given
    givenSource(new SourceSchedule(Optional.empty(), Optional.of(HOURLY)));
    givenSnapshotCompleted();

    // when
    SourceSupervisor.Cycle cycle = runCycle();

    // then
    assertThat(attemptedMode(cycle)).isEqualTo(CollectionMode.SNAPSHOT);
  }

  @Test
  @DisplayName("소스별 스냅샷 주기가 데몬 스냅샷 주기보다 우선한다")
  void perSourceSnapshotOverridesDaemon() {
    // given
    givenSource(new SourceSchedule(Optional.empty(), Optional.of(DAILY)));
    givenSnapshotCompleted();

    // when
    SourceSupervisor.Cycle cycle = runCycle(Optional.of(HOURLY));

    // then
    assertThat(attemptedMode(cycle)).isEqualTo(CollectionMode.INCREMENTAL);
  }

  @Test
  @DisplayName("소스별 스냅샷 주기가 없으면 데몬 스냅샷 주기를 쓴다")
  void daemonSnapshotWithoutPerSourceValue() {
    // given
    givenSource(SourceSchedule.DAEMON_DEFAULTS);
    givenSnapshotCompleted();

    // when
    SourceSupervisor.Cycle cycle = runCycle(Optional.of(HOURLY));

    // then
    assertThat(attemptedMode(cycle)).isEqualTo(CollectionMode.SNAPSHOT);
  }
}
