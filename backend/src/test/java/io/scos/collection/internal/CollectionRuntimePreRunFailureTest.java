package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 점유 뒤 run 시작 전 단계가 던지면 점유를 즉시 돌려준다(메모리 포트): 저장된 체크포인트 읽기, 어댑터 coverage 판정, run id·heartbeat 준비. 다음
 * worker가 TTL을 기다리지 않는다. TS는 체크포인트·coverage를 점유 **전에** 읽어(collection-runtime.ts collectSource) 같은
 * 실패가 점유도 run도 남기지 않는다 — Java는 체크포인트를 점유 뒤에 읽으므로(B-M-2) 해제로 같은 결과를 낸다. run은 시작 전이라 기록되지 않는다.
 * PostgreSQL 판은 {@code postgres.CollectionRuntimePreRunFailureTest}.
 */
@DisplayName("run 시작 전 단계가 실패하면 점유를 즉시 돌려준다(B-M-2)")
class CollectionRuntimePreRunFailureTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final RuntimeException INJECTED = new IllegalStateException("injected pre-run failure");

  final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
  final MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  final MemoryRunStore runs = new MemoryRunStore();
  final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();

  /** 체크포인트 읽기가 던지는 포트. */
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

  /** coverage 판정이 던지는 어댑터. */
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

  static Stream<Arguments> preRunSteps() {
    return Stream.of(
        Arguments.of(
            "체크포인트 읽기",
            (UnaryOperator<MemoryCollectionRuntime>) r -> r.checkpoints(FAILING_CHECKPOINT_READ)),
        Arguments.of(
            "coverage 판정",
            (UnaryOperator<MemoryCollectionRuntime>) r -> r.adapter(FAILING_COVERAGE)),
        Arguments.of(
            "run id·heartbeat 준비",
            (UnaryOperator<MemoryCollectionRuntime>)
                r ->
                    r.runIds(
                        () -> {
                          throw INJECTED;
                        })));
  }

  Throwable collectFailingAt(UnaryOperator<MemoryCollectionRuntime> failingStep) {
    sources.save(SourceInstances.noop(SOURCE, clock.instant()));
    CollectionRuntime runtime =
        failingStep.apply(MemoryCollectionRuntime.over(clock, sources, claims, runs)).build();
    return catchThrowable(
        () -> runtime.collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none()));
  }

  /** 다른 worker(w2)가 이 소스를 점유해 본다. */
  Optional<WorkClaim> claimByAnotherWorker() {
    return claims.tryClaim(
        SOURCE, new OwnerId("w2"), MemoryCollectionRuntime.LEASE_TTL, clock.instant());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("preRunSteps")
  @DisplayName("실패는 그대로 전파된다")
  void propagatesFailure(String step, UnaryOperator<MemoryCollectionRuntime> failingStep) {
    // when
    Throwable thrown = collectFailingAt(failingStep);

    // then
    assertThat(thrown).isSameAs(INJECTED);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("preRunSteps")
  @DisplayName("다음 worker가 바로 점유한다")
  void nextWorkerClaimsImmediately(
      String step, UnaryOperator<MemoryCollectionRuntime> failingStep) {
    // given
    collectFailingAt(failingStep);

    // when
    Optional<WorkClaim> next = claimByAnotherWorker();

    // then
    assertThat(next).isPresent();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("preRunSteps")
  @DisplayName("run을 기록하지 않는다(TS와 같음)")
  void recordsNoRun(String step, UnaryOperator<MemoryCollectionRuntime> failingStep) {
    // when
    collectFailingAt(failingStep);

    // then
    assertThat(runs.latest(SOURCE)).isEmpty();
  }
}
