package io.scos.collection.internal;

import io.scos.spi.connector.AbsenceVerification;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SnapshotScope;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 실제 런타임에서 검증 선언과 수집 결과의 독립성을 확인한다. */
public final class RuntimeAbsenceVerificationScenario {
  static final SnapshotScope SCOPE =
      new SnapshotScope(BodyCommitScenario.FINGERPRINT, Set.of("notion.page"));
  final CommitContext.Ports ports;
  final CollectionRuntime runtime;
  private boolean verify;
  private Runnable beforeReturn = () -> {};

  public RuntimeAbsenceVerificationScenario(
      CommitContext.Ports ports, Function<SourceAdapter, CollectionRuntime> runtime) {
    this.ports = ports;
    ports.sources().save(SourceInstances.noop(BodyCommitScenario.SOURCE, BodyCommitScenario.NOW));
    for (String id : List.of("seen", "unverified"))
      ports
          .rawItems()
          .apply(BodyCommitScenario.SOURCE, RawItems.item(id).resourceType("notion.page").upsert());
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
              public Optional<SnapshotScope> snapshotVerification(SourceConfig config) {
                return Optional.of(SCOPE);
              }

              @Override
              public CollectionCoverage coverage(
                  SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
                return CollectionCoverage.COMPLETE_SNAPSHOT;
              }

              @Override
              public void collect(CollectionInput input, PageSink sink) {
                sink.accept(
                    new CollectionPage(
                        List.of(RawItems.item("seen").resourceType("notion.page").upsert()),
                        List.of(),
                        new Checkpoint(4, "{}")));
                if (verify) {
                  var candidates = input.state().absences(input.cancel());
                  sink.accept(
                      new CollectionPage(
                          List.of(),
                          List.of(),
                          new Checkpoint(4, "{}"),
                          List.of(),
                          Optional.of(
                              new AbsenceVerification(
                                  SCOPE.fingerprint(), candidates.token(), Set.of("unverified")))));
                }
                beforeReturn.run();
              }
            });
  }

  public CollectionRuntime.Report collect() {
    return runtime.collect(
        BodyCommitScenario.SOURCE, CollectionMode.SNAPSHOT, CancellationToken.none());
  }

  public void verifyOutsideCandidate() {
    verify = true;
  }

  public void beforeReturn(Runnable action) {
    beforeReturn = action;
  }

  public Optional<RunStore.SnapshotCompletion> lastSnapshotCompletion() {
    return ports.runs().lastSnapshotCompletion(BodyCommitScenario.SOURCE);
  }

  public RunStatus latestStatus() {
    return ports.runs().latest(BodyCommitScenario.SOURCE).orElseThrow().status();
  }

  public boolean claimIsAvailable() {
    return ports
        .claims()
        .tryClaim(
            BodyCommitScenario.SOURCE,
            new OwnerId("after-failed-start"),
            Duration.ofMinutes(1),
            BodyCommitScenario.NOW)
        .isPresent();
  }
}
