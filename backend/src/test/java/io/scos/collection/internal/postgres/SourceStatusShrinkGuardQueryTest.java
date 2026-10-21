package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SourceQueries;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `source status`가 읽는 스냅샷 축소 보호 상태(ADR-0028): 아직 풀리지 않은 보호 판정과 소모되지 않은 허용 표시. TS에 없는 값이라 TS 비교
 * 각본(QueryCommandsTsEquivalenceTest)에는 나오지 않는다.
 */
@Tag("db")
@DisplayName("source status 축소 보호 상태 — 보호 판정과 대기 중 허용을 보인다(ADR-0028)")
class SourceStatusShrinkGuardQueryTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("tiro-main");
  static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");
  static final Instant GUARDED = T0.plusSeconds(60);
  static final Instant ACCEPTED = T0.plusSeconds(120);
  static final Instant CONSUMED = T0.plusSeconds(180);

  PostgresCollectionConfiguration.Adapters adapters;

  @BeforeEach
  void givenRegisteredSource() {
    PostgresSupport.truncateAll();
    adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
    adapters.sources().save(SourceInstances.noop(SOURCE, T0));
  }

  SourceQueries.SourceStatus status() {
    return adapters.queries().status(SOURCE, CONSUMED).orElseThrow();
  }

  @Nested
  @DisplayName("보호 판정이 남아 있고 허용 표시가 대기 중인 소스에서")
  class GuardedAndAccepted {
    @BeforeEach
    void givenGuardedThenAccepted() {
      adapters
          .guards()
          .recordGuarded(
              SOURCE, new SnapshotGuardStore.Guarded(new RunId("run-7"), GUARDED, 20, 20, 3));
      adapters.guards().acceptShrink(SOURCE, ACCEPTED);
    }

    @Test
    @DisplayName("상태는 그 보호 판정을 보인다")
    void showsGuard() {
      // when
      SourceQueries.SourceStatus status = status();

      // then
      assertThat(status.reconcileGuard())
          .contains(new SnapshotGuardStore.Guarded(new RunId("run-7"), GUARDED, 20, 20, 3));
    }

    @Test
    @DisplayName("상태는 허용 표시 시각을 보인다")
    void showsPendingAcceptance() {
      // when
      SourceQueries.SourceStatus status = status();

      // then
      assertThat(status.shrinkAcceptedAt()).contains(ACCEPTED);
    }
  }

  @Nested
  @DisplayName("허용 표시를 run이 이미 쓴 소스에서")
  class ConsumedAcceptance {
    @BeforeEach
    void givenConsumedAcceptance() {
      adapters.guards().acceptShrink(SOURCE, ACCEPTED);
      if (!adapters.guards().consumeAcceptance(SOURCE, new RunId("run-8"), CONSUMED))
        throw new IllegalStateException("given 준비 실패: 허용 표시를 소모하지 못했다");
    }

    @Test
    @DisplayName("상태는 허용 표시를 보이지 않는다")
    void hidesConsumedAcceptance() {
      // when
      SourceQueries.SourceStatus status = status();

      // then
      assertThat(status.shrinkAcceptedAt()).isEmpty();
    }
  }

  @Nested
  @DisplayName("실패한 run 뒤 보호된 succeeded run이 있는 소스에서")
  class GuardedAfterFailure {
    @BeforeEach
    void givenFailedThenGuardedRun() {
      finish("run-1", T0, RunStatus.FAILED, Optional.of("dependency_unavailable"));
      finish("run-2", GUARDED, RunStatus.SUCCEEDED, Optional.of("snapshot_shrink_guarded"));
    }

    @Test
    @DisplayName("보호된 run은 성공이라 최근 실패 수는 0이다")
    void guardedRunResetsFailures() {
      // when
      SourceQueries.SourceStatus status = status();

      // then
      assertThat(status.recentFailureCount()).isZero();
    }

    @Test
    @DisplayName("마지막 성공 시각은 보호된 run의 완료 시각이다")
    void guardedRunIsLastSuccess() {
      // when
      SourceQueries.SourceStatus status = status();

      // then
      assertThat(status.lastSuccessAt()).contains(GUARDED.plusSeconds(1));
    }
  }

  /** 시작·완료가 기록된 run 하나(완료는 시작 1초 뒤). */
  void finish(String id, Instant startedAt, RunStatus status, Optional<String> errorKind) {
    adapters.runs().start(new RunId(id), SOURCE, startedAt);
    adapters
        .runs()
        .finish(
            new RunId(id),
            status,
            CollectionCoverage.COMPLETE_SNAPSHOT,
            startedAt.plusSeconds(1),
            status.isSuccess() ? 0 : 1,
            errorKind,
            Optional.empty(),
            0);
  }

  @Nested
  @DisplayName("보호도 허용도 없는 소스에서")
  class Untouched {
    @Test
    @DisplayName("상태에 보호 판정이 없다")
    void noGuard() {
      // when
      SourceQueries.SourceStatus status = status();

      // then
      assertThat(status.reconcileGuard()).isEmpty();
    }
  }
}
