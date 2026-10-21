package io.scos.collection.internal;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.ManualTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 지표 의미(ADR-0009·ADR-0020 §3)를 SimpleMeterRegistry로 단언한다: run 결과·점유 경합·페이지 커밋 시간·heartbeat 결과가 실제
 * 사건 수와 같다. 외부 부작용(지표)이 계약이므로 값까지 본다.
 */
@DisplayName("수집 지표는 실제 사건 수와 같다(ADR-0009·ADR-0020 §3)")
class CollectionMetricsTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final Duration TTL = MemoryCollectionRuntime.LEASE_TTL;

  /** heartbeat tick 사이에 흐르는 시간(TTL/3) — 두 번 흘러도 TTL 안이다. */
  static final Duration TICK_STEP = TTL.dividedBy(3);

  static final Tags SUCCEEDED = Tags.of("status", "succeeded", "error_kind", "none");

  MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  SimpleMeterRegistry registry = new SimpleMeterRegistry();
  CollectionMetrics metrics = CollectionMetrics.of(registry);
  MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  MemoryRunStore runs = new MemoryRunStore();
  ManualTaskScheduler scheduler = new ManualTaskScheduler();

  CollectionRuntime.Report collect(SourceAdapter adapter) {
    sources.save(SourceInstances.noop(SOURCE, clock.instant()));
    return MemoryCollectionRuntime.over(clock, sources, claims, runs)
        .adapter(adapter)
        .heartbeats(scheduler)
        .metrics(metrics)
        .build()
        .collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  /** 한 항목짜리 페이지를 pages번 내는 어댑터. */
  static SourceAdapter pages(int pages) {
    return SourceInstances.paging(sink -> writePages(sink, pages));
  }

  /** 한 페이지를 낸 뒤 IllegalStateException으로 실패하는 어댑터. */
  static SourceAdapter failingAfterOnePage() {
    return SourceInstances.paging(
        sink -> {
          writePages(sink, 1);
          throw new IllegalStateException("boom");
        });
  }

  static void writePages(PageSink sink, int pages) {
    for (int i = 1; i <= pages; i++)
      sink.accept(
          CollectionPage.ofItems(
              List.of(RawItems.item("i" + i).build()),
              new Checkpoint(1, "{\"after\":\"i" + i + "\"}")));
  }

  /** 수집 중 heartbeat를 한 번 돌리는 어댑터. */
  SourceAdapter tickingOnce() {
    return SourceInstances.adapter(
        input -> {
          clock.advance(TICK_STEP);
          scheduler.tick();
        });
  }

  /** 수집하고, run이 끝까지(succeeded) 가지 않았으면 given 준비 실패로 멈춘다. */
  CollectionRuntime.Report collectToSuccess(SourceAdapter adapter) {
    CollectionRuntime.Report report = collect(adapter);
    if (report.status() != RunStatus.SUCCEEDED) {
      throw new IllegalStateException("given 준비 실패: run이 끝까지 가지 않았다 " + report.status());
    }
    return report;
  }

  /** 수집을 시도하고, AlreadyRunningException으로 거부되지 않았으면 given 준비 실패로 멈춘다. */
  void collectRejectedAsAlreadyRunning(SourceAdapter adapter) {
    Throwable thrown = catchThrowable(() -> collect(adapter));
    if (!(thrown instanceof AlreadyRunningException)) {
      throw new IllegalStateException("given 준비 실패: 수집이 점유 경합으로 거부되지 않았다", thrown);
    }
  }

  void givenClaimedByOtherWorker() {
    if (claims.tryClaim(SOURCE, new OwnerId("other"), TTL, clock.instant()).isEmpty()) {
      throw new IllegalStateException("given 준비 실패: 다른 worker가 점유하지 못했다");
    }
  }

  void givenHeartbeatStarted() {
    WorkClaim claim =
        claims
            .tryClaim(SOURCE, new OwnerId("w1"), TTL, clock.instant())
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: w1이 점유하지 못했다"));
    new LeaseHeartbeat(claims, claim, TTL, clock, scheduler, metrics).start();
  }

  void tickAfter(Duration elapsed) {
    clock.advance(elapsed);
    scheduler.tick();
  }

  @Nested
  @DisplayName("한 항목짜리 페이지 2개를 성공으로 수집하면")
  class TwoPagesSucceeded {
    @Test
    @DisplayName("run을 runs{status=succeeded,error_kind=none}로 센다")
    void successfulRunIsCounted() {
      // when
      collect(pages(2));

      // then
      assertThatMetrics(registry).hasCounter(CollectionMetrics.RUNS, SUCCEEDED, 1);
    }

    @Test
    @DisplayName("페이지 커밋 타이머는 커밋한 페이지 수만큼 기록된다")
    void pageCommitsAreTimed() {
      // when
      collect(pages(2));

      // then
      assertThatMetrics(registry).hasTimerCount(CollectionMetrics.PAGE_COMMIT, 2);
    }
  }

  @Nested
  @DisplayName("한 페이지를 낸 뒤 실패하면")
  class FailsAfterOnePage {
    @Test
    @DisplayName("run을 partially_succeeded와 예외 종류로 센다")
    void failedRunIsCountedWithKind() {
      // when
      collect(failingAfterOnePage());

      // then
      assertThatMetrics(registry)
          .hasCounter(
              CollectionMetrics.RUNS,
              Tags.of("status", "partially_succeeded", "error_kind", "IllegalStateException"),
              1);
    }

    @Test
    @DisplayName("run을 succeeded로 세지 않는다")
    void failedRunIsNotCountedAsSuccess() {
      // when
      collect(failingAfterOnePage());

      // then
      assertThatMetrics(registry).hasNoCounter(CollectionMetrics.RUNS, "status", "succeeded");
    }
  }

  @Test
  @DisplayName("점유가 있는 소스의 수집 시도는 claim_contention으로 센다")
  void contentionIsCounted() {
    // given
    givenClaimedByOtherWorker();

    // when
    collectRejectedAsAlreadyRunning(pages(1));

    // then
    assertThatMetrics(registry).hasCounter(CollectionMetrics.CLAIM_CONTENTION, Tags.empty(), 1);
  }

  @Test
  @DisplayName("수집 중 heartbeat tick은 런타임에 준 지표에 outcome=ok로 센다")
  void runtimeHeartbeatIsCounted() {
    // when
    collectToSuccess(tickingOnce());

    // then
    assertThatMetrics(registry).hasCounter(CollectionMetrics.LEASE_HEARTBEAT, "outcome", "ok", 1);
  }

  @Nested
  @DisplayName("heartbeat를 시작한 상태에서")
  class HeartbeatStarted {
    @BeforeEach
    void givenStarted() {
      givenHeartbeatStarted();
    }

    @Test
    @DisplayName("만료 전 tick은 outcome=ok로 센다")
    void liveHeartbeatsAreOk() {
      // when: TTL/3씩 두 번 tick하면(TTL 안)
      tickAfter(TICK_STEP);
      tickAfter(TICK_STEP);

      // then
      assertThatMetrics(registry).hasCounter(CollectionMetrics.LEASE_HEARTBEAT, "outcome", "ok", 2);
    }

    @Test
    @DisplayName("만료 뒤 tick은 outcome=failed로 센다")
    void expiredHeartbeatFails() {
      // when
      tickAfter(TTL.plus(TICK_STEP));

      // then
      assertThatMetrics(registry)
          .hasCounter(CollectionMetrics.LEASE_HEARTBEAT, "outcome", "failed", 1);
    }
  }

  @Test
  @DisplayName("of(registry)의 registry()는 같은 레지스트리를 돌려준다")
  void registryIsShared() {
    // when
    MeterRegistry shared = metrics.registry();

    // then
    assertThat(shared).isSameAs(registry);
  }
}
