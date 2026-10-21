package io.scos.server;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryUnitStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 데몬 게이지(ADR-0009·ADR-0020 §3): scos.projection.requests{state},
 * scos.projection.consecutive_failures, scos.engine.freshness_hours — 값은 수집 시점(스크레이프)마다 포트에서 읽는다.
 */
@DisplayName("데몬 게이지는 투영 요청·연속 실패·신선도를 스크레이프마다 포트에서 읽는다(ADR-0020 §3)")
class DaemonGaugesTest {
  static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
  MutableClock clock = new MutableClock(NOW);
  MemoryOutboxPort outbox = new MemoryOutboxPort();
  MemoryUnitStore units = new MemoryUnitStore();
  SimpleMeterRegistry registry = new SimpleMeterRegistry();
  SpaceId legacy = new SpaceId("legacy");
  SpaceId team = new SpaceId("team");

  /** 게이지는 상태 객체를 약한 참조로 잡는다(Micrometer) — 필드로 붙들지 않으면 GC 뒤 게이지가 NaN이 되고 요약을 읽지 않는다. */
  DaemonGauges gauges;

  void bind() {
    gauges =
        new DaemonGauges(outbox, new FreshnessReader(units, clock), Set.of(legacy, team), clock);
    gauges.bindTo(registry);
  }

  /** 다음 지표 요청은 요약을 새로 읽는다(재사용 창이 지났다). */
  void nextScrape() {
    clock.advance(DaemonGauges.SUMMARY_REUSE);
  }

  static final ProjectionRetryPolicy PARK_AFTER_THREE =
      new ProjectionRetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 3);
  static final SourceInstanceId A = new SourceInstanceId("a");
  static final SourceInstanceId B = new SourceInstanceId("b");
  static final SourceInstanceId C = new SourceInstanceId("c");

  /** a·b는 대기 중, c는 세 번 실패해 parked. */
  void givenTwoPendingAndOneParked() {
    for (SourceInstanceId source : List.of(A, B, C)) outbox.requestProjection(source, NOW);
    for (int i = 0; i < 3; i++)
      outbox.recordFailure(
          C, outbox.request(C).orElseThrow().version(), NOW, "e", PARK_AFTER_THREE);
    bind();
  }

  void givenUnitCapturedHoursAgo(SpaceId space, String unitId, int hours) {
    units.upsert(
        space,
        List.of(
            ContextUnits.unit(unitId)
                .capturedAt(NOW.minus(Duration.ofHours(hours)).toString())
                .build()));
  }

  /** Outbox 요약 질의 수를 센다. */
  static final class CountingOutbox extends MemoryOutboxPort {
    int summaryQueries;

    @Override
    public synchronized Summary summary() {
      summaryQueries++;
      return super.summary();
    }
  }

  static final Tags PENDING = Tags.of("state", "pending");
  static final Tags PARKED = Tags.of("state", "parked");

  /** 한 번의 지표 요청이 읽는 요청 게이지 셋(값을 읽는 것 자체가 요약 질의를 부른다). */
  void scrapeRequestGauges() {
    pendingGauge();
    registry.get(DaemonGauges.PROJECTION_REQUESTS).tags(PARKED).gauge().value();
    registry.get(DaemonGauges.CONSECUTIVE_FAILURES).gauge().value();
  }

  double pendingGauge() {
    return registry.get(DaemonGauges.PROJECTION_REQUESTS).tags(PENDING).gauge().value();
  }

  @Nested
  @DisplayName("a·b가 대기 중이고 c가 parked인 Outbox에서")
  class TwoPendingOneParked {
    @BeforeEach
    void given() {
      givenTwoPendingAndOneParked();
    }

    @Test
    @DisplayName("투영 요청 게이지는 대기 중인 요청 수를 pending으로 보고한다")
    void reportsPending() {
      // when·then: 게이지 값은 읽을 때 계산된다
      assertThatMetrics(registry).hasGauge(DaemonGauges.PROJECTION_REQUESTS, PENDING, 2);
    }

    @Test
    @DisplayName("투영 요청 게이지는 parked 요청 수를 보고한다")
    void reportsParked() {
      // when·then
      assertThatMetrics(registry).hasGauge(DaemonGauges.PROJECTION_REQUESTS, PARKED, 1);
    }

    @Test
    @DisplayName("연속 실패 게이지는 요청들 중 최댓값을 보고한다")
    void reportsWorstConsecutiveFailures() {
      // when·then
      assertThatMetrics(registry).hasGauge(DaemonGauges.CONSECUTIVE_FAILURES, Tags.empty(), 3);
    }

    @Test
    @DisplayName("요청 하나가 끝나면 다음 지표 요청에서 Outbox를 다시 읽는다")
    void rereadsOnNextScrape() {
      // given: 첫 지표 요청이 pending 2를 읽은 뒤 a가 끝난다
      if (pendingGauge() != 2)
        throw new IllegalStateException("given 준비 실패: 첫 지표 요청의 pending이 2가 아니다");
      outbox.complete(A, outbox.request(A).orElseThrow().version());

      // when
      nextScrape();

      // then
      assertThatMetrics(registry).hasGauge(DaemonGauges.PROJECTION_REQUESTS, PENDING, 1);
    }
  }

  @Test
  @DisplayName("한 번의 지표 요청에서 요청 게이지 셋을 읽어도 요약 질의는 한 번이다")
  void oneSummaryQueryPerScrape() {
    // given
    CountingOutbox counting = new CountingOutbox();
    outbox = counting;
    bind();

    // when
    scrapeRequestGauges();

    // then
    assertThat(counting.summaryQueries).isEqualTo(1);
  }

  @Test
  @DisplayName("freshness_hours는 공간들 중 가장 오래된 데이터의 나이다")
  void freshnessIsWorstAcrossSpaces() {
    // given
    givenUnitCapturedHoursAgo(legacy, "l-1", 2);
    givenUnitCapturedHoursAgo(team, "t-1", 5);
    bind();

    // when·then
    assertThatMetrics(registry).hasGauge(DaemonGauges.FRESHNESS_HOURS, Tags.empty(), 5.0);
  }

  @Test
  @DisplayName("데이터 나이를 모르면 freshness_hours는 0이 아니라 NaN이다")
  void freshnessUnavailableIsNaN() {
    // given: 유닛이 없으면 신선하다고 보고하지 않는다
    bind();

    // when·then
    assertThatMetrics(registry).hasGauge(DaemonGauges.FRESHNESS_HOURS, Tags.empty(), Double.NaN);
  }
}
