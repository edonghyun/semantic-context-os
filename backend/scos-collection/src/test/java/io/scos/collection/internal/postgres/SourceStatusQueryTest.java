package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SourceQueries;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * source status의 집계 중 TS 비교 각본(ops.QueryCommandsTsEquivalenceTest)이 가르지 못하는 갈래: 성공 이전의 실패는 세지 않는다,
 * running run, 만료된 점유, 없는 소스.
 */
@Tag("db")
@DisplayName("source status는 마지막 성공 뒤 실패만 세고 살아 있는 점유만 싣는다")
class SourceStatusQueryTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-status");
  static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");
  static final Duration STEP = Duration.ofMinutes(1);
  static final Duration LEASE_TTL = Duration.ofSeconds(30);

  static void finished(
      PostgresCollectionConfiguration.Adapters adapters, String id, Instant at, RunStatus status) {
    adapters.runs().start(new RunId(id), SOURCE, at);
    adapters
        .runs()
        .finish(
            new RunId(id),
            status,
            CollectionCoverage.INCREMENTAL,
            at.plusSeconds(1),
            status.isSuccess() ? 0 : 1,
            status.isSuccess() ? Optional.empty() : Optional.of("dependency_unavailable"),
            status.isSuccess() ? Optional.empty() : Optional.of("down"),
            0);
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("실패 → 성공 → 부분 성공 → 실행 중 순서의 run과 만료된 점유가 있으면")
  class RunHistoryWithExpiredLease {
    SourceQueries.SourceStatus status;
    List<String> newestTwoRuns;
    Optional<SourceQueries.SourceStatus> missing;

    @BeforeAll
    void runs() {
      PostgresSupport.truncateAll();
      var adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
      adapters.sources().save(SourceInstances.noop(SOURCE, T0));
      finished(adapters, "r1", T0, RunStatus.FAILED);
      finished(adapters, "r2", T0.plus(STEP), RunStatus.SUCCEEDED);
      finished(adapters, "r3", T0.plus(STEP.multipliedBy(2)), RunStatus.PARTIALLY_SUCCEEDED);
      adapters.runs().start(new RunId("r4"), SOURCE, T0.plus(STEP.multipliedBy(3)));
      if (adapters.claims().tryClaim(SOURCE, new OwnerId("w1"), LEASE_TTL, T0).isEmpty())
        throw new IllegalStateException("given 준비 실패: 점유를 잡지 못했다");
      Instant now = T0.plus(STEP.multipliedBy(4));
      status =
          adapters
              .queries()
              .status(SOURCE, now)
              .orElseThrow(() -> new IllegalStateException("given 준비 실패: 소스 상태가 없다"));
      missing = adapters.queries().status(new SourceInstanceId("absent"), now);
      newestTwoRuns =
          adapters.queries().recentRuns(2).stream().map(run -> run.id().value()).toList();
    }

    @Test
    @DisplayName("최근 실패 수는 마지막 성공 뒤의 것만 센다")
    void failuresSinceSuccess() {
      // then
      assertThat(status.recentFailureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("running run이 있으면 running이다")
    void running() {
      // then
      assertThat(status.running()).isTrue();
    }

    @Test
    @DisplayName("마지막 성공 시각은 그 run의 종료 시각이다")
    void lastSuccess() {
      // then
      assertThat(status.lastSuccessAt()).contains(T0.plus(STEP).plusSeconds(1));
    }

    @Test
    @DisplayName("만료된 점유는 싣지 않는다")
    void expiredLeaseOmitted() {
      // then
      assertThat(status.activeLeaseExpiresAt()).isEmpty();
    }

    @Test
    @DisplayName("없는 소스는 empty다")
    void missingSource() {
      // then
      assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("최근 run 조회는 최근 것부터 limit건만 준다")
    void recentRunsLimited() {
      // then
      assertThat(newestTwoRuns).containsExactly("r4", "r3");
    }
  }
}
