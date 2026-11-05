package io.scos.collection.internal;

import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 페이지 사이에 실제 소스 등록을 바꾸는 런타임 시나리오. */
public final class RuntimeConfigurationFenceScenario {
  static final String REMOVED = "block:removed";
  final CommitContext.Ports ports;
  final CollectionRuntime runtime;
  boolean snapshotAtReturn;

  public RuntimeConfigurationFenceScenario(
      CommitContext.Ports ports,
      BodyCompletionStore bodies,
      Function<SourceAdapter, CollectionRuntime> runtime) {
    this.ports = ports;
    ports.sources().save(SourceInstances.noop(BodyCommitScenario.SOURCE, BodyCommitScenario.NOW));
    ports
        .rawItems()
        .apply(
            BodyCommitScenario.SOURCE,
            RawItems.item(BodyCommitScenario.PAGE)
                .version("v1")
                .order(BodyCommitScenario.ORDER)
                .upsert());
    for (String id : List.of(BodyCommitScenario.BLOCK, REMOVED))
      ports
          .rawItems()
          .apply(
              BodyCommitScenario.SOURCE, RawItems.item(id).resourceType("notion.block").upsert());
    bodies.save(
        BodyCommitScenario.SOURCE,
        new BodyCompletionStore.Body(
            BodyCommitScenario.FINGERPRINT,
            BodyCommitScenario.PAGE,
            "v1",
            BodyCommitScenario.ORDER,
            BodyCommitScenario.NOW,
            Set.of(BodyCommitScenario.BLOCK, REMOVED)));
    this.runtime =
        runtime.apply(
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
                return snapshotAtReturn
                    ? CollectionCoverage.COMPLETE_SNAPSHOT
                    : CollectionCoverage.INCREMENTAL;
              }

              @Override
              public void collect(CollectionInput input, PageSink sink) {
                collectPages(sink);
              }
            });
  }

  private void collectPages(PageSink sink) {
    sink.accept(
        new CollectionPage(
            List.of(
                RawItems.item(BodyCommitScenario.PAGE)
                    .version("v1")
                    .order(BodyCommitScenario.ORDER)
                    .upsert()),
            List.of(),
            new Checkpoint(4, "{}")));
    changeConfiguration();
    if (!snapshotAtReturn) sink.accept(completedBody());
  }

  public void changeBeforeSnapshotReturns() {
    snapshotAtReturn = true;
  }

  public boolean snapshotCompleted() {
    return ports.runs().lastSnapshotCompletion(BodyCommitScenario.SOURCE).isPresent();
  }

  private void changeConfiguration() {
    var original = ports.sources().get(BodyCommitScenario.SOURCE).orElseThrow();
    ports
        .sources()
        .save(
            new SourceInstance(
                original.id(),
                original.kind(),
                original.displayName(),
                new SourceConfig(1, "{\"generation\":\"changed\"}"),
                original.credentialRef(),
                original.enabled(),
                original.schedule(),
                original.shrinkGuard(),
                original.createdAt(),
                BodyCommitScenario.NOW));
  }

  private CollectionPage completedBody() {
    return new CollectionPage(
        List.of(),
        List.of(),
        new Checkpoint(4, "{}"),
        List.of(
            new BodyObservation(
                BodyCommitScenario.FINGERPRINT,
                BodyCommitScenario.PAGE,
                "v1",
                BodyCommitScenario.ORDER,
                Set.of(BodyCommitScenario.BLOCK),
                BodyObservation.Outcome.COMPLETE)));
  }

  public CollectionRuntime.Report collect() {
    return runtime.collect(
        BodyCommitScenario.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  public boolean removedBlockIsActive() {
    return ports.rawItems().get(BodyCommitScenario.SOURCE, REMOVED).orElse(null)
        instanceof RawRecord.Active;
  }
}
