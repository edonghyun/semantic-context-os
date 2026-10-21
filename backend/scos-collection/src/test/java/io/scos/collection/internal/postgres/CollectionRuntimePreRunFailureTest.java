package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.StoredCheckpoint;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 점유 뒤 run 시작 전 실패의 PostgreSQL 판(실제 점유·run 저장소): 체크포인트 읽기·coverage 판정·run id/heartbeat 준비 중 어디서 던져도
 * 점유가 바로 풀려 다음 worker가 TTL 없이 점유하고, run은 기록되지 않는다. 세 지점을 파라미터로 돈다. 메모리 판은 {@code
 * collection.CollectionRuntimePreRunFailureTest}.
 */
@Tag("db")
@DisplayName("run 시작 전 실패는 점유를 바로 풀고 run을 남기지 않는다(PostgreSQL 판)")
class CollectionRuntimePreRunFailureTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-coverage");
  static final RuntimeException INJECTED = new IllegalStateException("injected coverage failure");

  static final SourceAdapter FAILING_COVERAGE =
      new SourceAdapter() {
        @Override
        public String kind() {
          return SourceInstances.NOOP_KIND;
        }

        @Override
        public void validateConfig(SourceConfig config) {}

        @Override
        public CollectionCoverage coverage(
            SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
          throw INJECTED;
        }

        @Override
        public void collect(CollectionInput input, PageSink sink) {}
      };

  static final CheckpointPort FAILING_CHECKPOINT_READ =
      new CheckpointPort() {
        @Override
        public StoredCheckpoint read(SourceInstanceId source) {
          throw INJECTED;
        }

        @Override
        public long advance(
            SourceInstanceId source, long expectedRevision, Checkpoint next, Instant now) {
          throw new AssertionError("run이 시작되지 않았다");
        }
      };

  final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));

  /** 한 상황의 기록: 던진 예외, 다음 worker의 즉시 점유 여부, 최근 run. */
  record Outcome(Throwable failure, boolean nextWorkerClaimed, Optional<RunId> latestRun) {}

  Outcome collectFailingAt(UnaryOperator<PostgresCollectionRuntime> failingStep) {
    PostgresSupport.truncateAll();
    DataSource ds = PostgresSupport.dataSource();
    var adapters = PostgresCollectionConfiguration.adapters(ds);
    adapters.sources().save(SourceInstances.noop(SOURCE, clock.instant()));
    Throwable failure =
        catchThrowable(
            () ->
                failingStep
                    .apply(PostgresCollectionRuntime.over(ds, clock))
                    .build()
                    .collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none()));
    boolean claimed =
        adapters
            .claims()
            .tryClaim(
                SOURCE, new OwnerId("w2"), PostgresCollectionRuntime.LEASE_TTL, clock.instant())
            .isPresent();
    return new Outcome(
        failure, claimed, new PostgresCollectionObservations(ds, SOURCE, clock).latestRunId());
  }

  @Nested
  @DisplayName("점유 뒤 run 시작 전 단계가 던지면")
  class PreRunStepFails {
    static Stream<Arguments> preRunSteps() {
      return Stream.of(
          Arguments.of(
              "체크포인트 읽기",
              (UnaryOperator<PostgresCollectionRuntime>)
                  r ->
                      r.adapter(SourceInstances.NOOP_ADAPTER)
                          .checkpointReads(FAILING_CHECKPOINT_READ)),
          Arguments.of(
              "coverage 판정",
              (UnaryOperator<PostgresCollectionRuntime>) r -> r.adapter(FAILING_COVERAGE)),
          Arguments.of(
              "run id·heartbeat 준비",
              (UnaryOperator<PostgresCollectionRuntime>)
                  r ->
                      r.adapter(SourceInstances.NOOP_ADAPTER)
                          .runIds(
                              () -> {
                                throw INJECTED;
                              })));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preRunSteps")
    @DisplayName("실패는 그대로 전파된다")
    void propagatesFailure(String step, UnaryOperator<PostgresCollectionRuntime> failingStep) {
      // when
      Outcome outcome = collectFailingAt(failingStep);

      // then
      assertThat(outcome.failure()).isSameAs(INJECTED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preRunSteps")
    @DisplayName("다음 worker가 바로 점유한다")
    void nextWorkerClaimsImmediately(
        String step, UnaryOperator<PostgresCollectionRuntime> failingStep) {
      // when
      Outcome outcome = collectFailingAt(failingStep);

      // then
      assertThat(outcome.nextWorkerClaimed()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preRunSteps")
    @DisplayName("run을 기록하지 않는다(TS와 같음)")
    void recordsNoRun(String step, UnaryOperator<PostgresCollectionRuntime> failingStep) {
      // when
      Outcome outcome = collectFailingAt(failingStep);

      // then
      assertThat(outcome.latestRun()).isEmpty();
    }
  }
}
