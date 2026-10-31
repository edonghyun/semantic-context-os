package io.scos.collection.internal;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.memory.MemoryBodyCompletionStore;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.ReconciliationContext;
import io.scos.support.LogCapture;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 실제 runtime·페이지 TX·run 완료 뒤의 원장 보고를 관찰한다. */
final class ReconciliationReportingScenario implements AutoCloseable {
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final CollectionMetrics metrics = CollectionMetrics.of(registry);
  final LogCapture log = LogCapture.of(CollectionRuntime.class);
  final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  final MemoryRunStore runs = new MemoryRunStore();
  final MemoryRawItemStore raw = new MemoryRawItemStore();
  final MemoryBodyCompletionStore bodies = new MemoryBodyCompletionStore();
  final MutableClock clock = new MutableClock(ReconciliationDiscoveryScenario.NOW);
  boolean failAfterPage;
  boolean emitPage = true;
  boolean bootstrap;

  ReconciliationReportingScenario() {
    sources.save(SourceInstances.noop(ReconciliationDiscoveryScenario.SOURCE, clock.instant()));
    raw.apply(
        ReconciliationDiscoveryScenario.SOURCE,
        RawItems.item(ReconciliationDiscoveryScenario.PAGE)
            .version(ReconciliationDiscoveryScenario.OLD_VERSION)
            .order(ReconciliationDiscoveryScenario.OLD_ORDER)
            .upsert());
    bodies.save(
        ReconciliationDiscoveryScenario.SOURCE,
        new BodyCompletionStore.Body(
            ReconciliationDiscoveryScenario.FINGERPRINT,
            ReconciliationDiscoveryScenario.PAGE,
            ReconciliationDiscoveryScenario.OLD_VERSION,
            ReconciliationDiscoveryScenario.OLD_ORDER,
            clock.instant(),
            Set.of()));
  }

  static final long CLEANUP_AGE_SECONDS = 30;
  static final String REPORT_FAILURE_MESSAGE = "synthetic reporting failure";

  void cleanupCompletedEarlier() {
    runs.recordSnapshotCompletion(
        ReconciliationDiscoveryScenario.SOURCE,
        clock.instant().minusSeconds(CLEANUP_AGE_SECONDS),
        0);
  }

  void reportingBackendFails() {
    registry
        .config()
        .meterFilter(
            new MeterFilter() {
              @Override
              public Meter.Id map(Meter.Id id) {
                if (id.getName().equals(CollectionMetrics.RECONCILIATION_DISCOVERED))
                  throw new IllegalStateException(REPORT_FAILURE_MESSAGE);
                return id;
              }
            });
  }

  void collect() {
    MemoryCollectionRuntime.over(clock, sources, new MemoryWorkClaimPort(), runs)
        .runIds(() -> ReconciliationDiscoveryScenario.RUN)
        .rawItems(raw)
        .bodies(bodies)
        .metrics(metrics)
        .adapter(SourceInstances.paging(this::produce))
        .build()
        .collect(
            ReconciliationDiscoveryScenario.SOURCE,
            CollectionMode.INCREMENTAL,
            CancellationToken.none());
  }

  private void produce(PageSink sink) {
    if (!emitPage) return;
    var context =
        ReconciliationContexts.withDefaultGrace(
            ReconciliationContext.Path.FULL,
            Optional.of(ReconciliationDiscoveryScenario.LAST_SCAN),
            Optional.of(ReconciliationDiscoveryScenario.GRACE_BOUNDARY),
            bootstrap);
    sink.accept(
        new CollectionPage(
            List.of(
                RawItems.item(ReconciliationDiscoveryScenario.PAGE)
                    .version(ReconciliationDiscoveryScenario.NEW_VERSION)
                    .order(ReconciliationDiscoveryScenario.NEW_ORDER)
                    .upsert()),
            List.of(),
            new Checkpoint(1, "{}"),
            List.of(
                new BodyObservation(
                    ReconciliationDiscoveryScenario.FINGERPRINT,
                    ReconciliationDiscoveryScenario.PAGE,
                    ReconciliationDiscoveryScenario.NEW_VERSION,
                    ReconciliationDiscoveryScenario.NEW_ORDER,
                    Set.of(),
                    BodyObservation.Outcome.COMPLETE,
                    Optional.of(context)))));
    if (failAfterPage) throw new IllegalStateException("synthetic collection failure");
  }

  JsonNode reportDetail() {
    return log.events("collection.reconciliation.completed").getFirst().path("detail");
  }

  @Override
  public void close() {
    log.close();
    registry.close();
  }
}
