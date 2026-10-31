package io.scos.collection.internal;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.support.TestMetrics;
import java.util.Optional;

/** 실제 포트와 커밋 위에서 설정 변경 시점을 마련한다. */
public final class ConfigurationFenceScenario {
  final CommitContext.Ports ports;
  final BodyCommitScenario body;
  final SourceDescriptor expected;
  final PageCommit pageCommit;
  final RunCompletion completion;

  public ConfigurationFenceScenario(CommitContext.Ports ports, BodyCompletionStore bodies) {
    this.ports = ports;
    body = new BodyCommitScenario(ports, bodies);
    var source =
        SourceInstances.scheduled(
            BodyCommitScenario.SOURCE, SourceSchedule.DAEMON_DEFAULTS, BodyCommitScenario.NOW);
    ports.sources().save(source);
    expected = source.descriptor();
    var stores = ports.stores().withBodies(bodies).withSources(ports.sources());
    completion =
        new RunCompletion(
            stores,
            new SnapshotShrinkGuard(
                ports.sources(),
                ports.guards(),
                SnapshotShrinkLimits.DEFAULTS,
                TestMetrics.collection()));
    pageCommit =
        new PageCommit(stores, body.clock, TestMetrics.collection(), new Progress(body.clock));
  }

  public void previousBody() {
    body.previousBodyHasRemovedBlock();
  }

  public void changeConfiguration() {
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
                body.clock.instant()));
  }

  public void completeBody() {
    pageCommit.commit(
        body.claim,
        BodyCommitScenario.RUN,
        body.expectedRevision,
        body.completedBodyPage(),
        expected);
  }

  public void completeSnapshot() {
    completion.complete(
        new RunCompletion.Input(
            body.claim,
            BodyCommitScenario.RUN,
            RunOutcome.succeeded(0),
            CollectionCoverage.COMPLETE_SNAPSHOT,
            body.clock.instant(),
            Optional.of(expected)));
  }

  public boolean snapshotCompleted() {
    return ports.runs().lastSnapshotCompletion(BodyCommitScenario.SOURCE).isPresent();
  }

  public boolean removedBlockIsActive() {
    return body.removedBlockIsActive();
  }
}
