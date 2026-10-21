package io.scos.connector.tiro.internal;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.MemoryCollectionRuntime;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tiro 종단(가짜 Tiro + 실제 런타임, 메모리 포트): Tiro는 늘 complete_snapshot이라 노트 목록이 200으로 비어서 오면 TS는 수집한 노트를 전부
 * 지운다. 스냅샷 축소 보호(ADR-0028, TS와 다른 의도한 차이)는 그 run을 정리 없이 succeeded로 끝낸다. 운영자가 허용하면 다음 수집이 지운다.
 */
@DisplayName("Tiro 빈 노트 목록 — 200으로 빈 목록이 와도 수집한 노트를 지우지 않는다(ADR-0028)")
class TiroShrinkGuardTest {
  static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
  static final String LIST = "/v1/external/workspaces/ws-synthetic/notes";
  static final String EMPTY_LISTING = "{\"content\":[],\"nextCursor\":null}";

  final MutableClock clock = new MutableClock(NOW);
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final MemoryRawItemStore rawItems = new MemoryRawItemStore();
  final MemorySnapshotGuardStore guards = new MemorySnapshotGuardStore();
  FakeTiroServer server;
  CollectionRuntime runtime;

  @BeforeEach
  void assemble() {
    server = TiroAssembly.server();
    MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
    sources.save(TiroAssembly.instance(server.configJson(), NOW));
    AtomicInteger runs = new AtomicInteger();
    runtime =
        MemoryCollectionRuntime.over(
                clock, sources, new MemoryWorkClaimPort(), new MemoryRunStore())
            .rawItems(rawItems)
            .guards(guards)
            .metrics(CollectionMetrics.of(registry))
            .adapter(TiroAssembly.adapter(server.baseUrl(), server.token(), clock))
            .runIds(() -> new RunId("run-" + runs.incrementAndGet()))
            .build();
  }

  @AfterEach
  void close() {
    try {
      server.assertNoUnmatched();
    } finally {
      server.close();
    }
  }

  CollectionRuntime.Report collect() {
    return runtime.collect(
        TiroAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  List<String> activeIds() {
    List<String> out = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    do {
      RawItemStore.Page page = rawItems.listActive(TiroAssembly.SOURCE, 100, cursor);
      page.items().forEach(i -> out.add(i.externalId()));
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  /** 첫 수집으로 노트를 적재했다(전제: active 노트가 있다). */
  List<String> givenNotesCollected() {
    collect();
    List<String> collected = activeIds();
    if (collected.isEmpty()) throw new IllegalStateException("given 준비 실패: 첫 수집이 노트를 적재하지 않았다");
    return collected;
  }

  @Nested
  @DisplayName("노트를 수집한 뒤 목록이 200으로 비어서 오면")
  class EmptyListing {
    List<String> collected;

    @BeforeEach
    void givenEmptyListingAfterCollection() {
      collected = givenNotesCollected();
      server.answer(LIST, EMPTY_LISTING);
    }

    @Test
    @DisplayName("수집한 노트가 그대로 active로 남는다")
    void keepsCollectedNotes() {
      // when
      collect();

      // then
      assertThat(activeIds()).containsExactlyElementsOf(collected);
    }

    @Test
    @DisplayName("run은 보호 사유가 붙은 succeeded로 끝난다")
    void runSucceedsWithGuardKind() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report)
          .extracting(
              CollectionRuntime.Report::status,
              CollectionRuntime.Report::errorKind,
              CollectionRuntime.Report::errorCount)
          .containsExactly(RunStatus.SUCCEEDED, Optional.of("snapshot_shrink_guarded"), 0);
    }

    @Test
    @DisplayName("수집 run 지표는 보호 사유를 오류 종류 태그로 단다")
    void runMetricCarriesGuardKind() {
      // when
      collect();

      // then
      assertThatMetrics(registry)
          .hasCounter(
              CollectionMetrics.RUNS,
              Tags.of("status", "succeeded", "error_kind", "snapshot_shrink_guarded"),
              1);
    }
  }

  @Nested
  @DisplayName("노트를 수집한 뒤 다시 수집하면")
  class Recollected {
    List<String> collected;

    @BeforeEach
    void givenNotesCollectedOnce() {
      collected = givenNotesCollected();
    }

    @Test
    @DisplayName("그 run의 축소 판정 분모로 시작 때 active 노트 수를 남긴다")
    void recordsBaselineAtStart() {
      // when
      collect();

      // then
      assertThat(guards.baseline(TiroAssembly.SOURCE, new RunId("run-2")))
          .contains(collected.size());
    }
  }

  @Nested
  @DisplayName("빈 목록으로 보호된 뒤 운영자가 축소를 허용하고 다시 수집하면")
  class AcceptedAfterGuard {
    @BeforeEach
    void givenGuardedThenAccepted() {
      givenNotesCollected();
      server.answer(LIST, EMPTY_LISTING);
      collect();
      if (activeIds().isEmpty()) throw new IllegalStateException("given 준비 실패: 빈 목록 run이 노트를 지웠다");
      guards.acceptShrink(TiroAssembly.SOURCE, clock.instant());
    }

    @Test
    @DisplayName("수집한 노트를 모두 지운다")
    void removesEveryNote() {
      // when
      collect();

      // then
      assertThat(activeIds()).isEmpty();
    }
  }
}
