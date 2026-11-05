package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.SourceInstanceId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * complete_snapshot run을 시작한 뒤 정리 기준(baseline) 기록이 실패하면, heartbeat 등록 실패와 같이 그 run을 그 자리에서 failed로
 * 마감한다 — 마감에 판정한 coverage를 넘긴다(CONTRACTS "수집 run 시작 실패의 마감").
 */
@DisplayName("complete_snapshot run 시작 뒤 정리 기준 기록이 실패하면")
class CollectionRuntimeSnapshotBaselineFailureTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final String BASELINE_FAILURE = "injected baseline failure";

  final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
  final MemoryRunStore runs = MemoryRunStore.create();

  /** 완전 스냅샷을 걷는 noop 종류 어댑터(수집은 하지 않는다 — 시작 단계에서 끝난다). */
  static final SourceAdapter SNAPSHOT_ADAPTER =
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
          return CollectionCoverage.COMPLETE_SNAPSHOT;
        }

        @Override
        public void collect(CollectionInput input, PageSink sink) {}
      };

  /** recordBaseline만 실패하고 나머지는 메모리 저장소에 넘기는 보호 상태 저장소. */
  static SnapshotGuardStore failingBaseline(SnapshotGuardStore delegate) {
    return (SnapshotGuardStore)
        Proxy.newProxyInstance(
            SnapshotGuardStore.class.getClassLoader(),
            new Class<?>[] {SnapshotGuardStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("recordBaseline"))
                throw new IllegalStateException(BASELINE_FAILURE);
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  Throwable collect() {
    MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
    sources.save(SourceInstances.noop(SOURCE, clock.instant()));
    CollectionRuntime runtime =
        MemoryCollectionRuntime.over(clock, sources, new MemoryWorkClaimPort(), runs)
            .adapter(SNAPSHOT_ADAPTER)
            .guards(failingBaseline(new MemorySnapshotGuardStore()))
            .build();
    return catchThrowable(
        () -> runtime.collect(SOURCE, CollectionMode.SNAPSHOT, CancellationToken.none()));
  }

  @Test
  @DisplayName("run은 complete_snapshot coverage의 failed로 마감된다")
  void runFinishedAsFailed() {
    // when
    collect();

    // then
    CollectionRun run = runs.latest(SOURCE).orElseThrow();
    assertThat(List.of(run.status(), run.coverage()))
        .containsExactly(RunStatus.FAILED, Optional.of(CollectionCoverage.COMPLETE_SNAPSHOT));
  }

  @Test
  @DisplayName("기준 기록 실패가 마감 실패 없이 그대로 전파된다")
  void propagatesWithoutSuppressed() {
    // when
    Throwable thrown = collect();

    // then
    assertThat(List.of(thrown.getMessage(), thrown.getSuppressed().length))
        .containsExactly(BASELINE_FAILURE, 0);
  }
}
