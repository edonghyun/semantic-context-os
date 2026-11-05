package io.scos.collection.internal;

import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 본문 커밋 계약의 점유·run·페이지 연결. */
public final class BodyCommitScenario {
  public static final SourceInstanceId SOURCE = new SourceInstanceId("body-commit");
  public static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  public static final String FINGERPRINT = "roots-generation";
  public static final String PAGE = "page";
  public static final String BLOCK = "block:one";
  public static final SourceOrder ORDER = new SourceOrder("notion.page.v1", "0001");
  static final RunId RUN = new RunId("body-commit-run");
  static final Duration TTL = Duration.ofMinutes(1);
  final CommitContext.Ports ports;
  final BodyCompletionStore bodies;
  final MutableClock clock = new MutableClock(NOW);
  final WorkClaim claim;
  final PageCommit commit;
  long expectedRevision;

  public BodyCommitScenario(CommitContext.Ports ports, BodyCompletionStore bodies) {
    this.ports = ports;
    this.bodies = bodies;
    claim = ports.claims().tryClaim(SOURCE, new OwnerId("body-worker"), TTL, NOW).orElseThrow();
    ports.runs().start(RUN, SOURCE, NOW);
    commit =
        new PageCommit(
            ports.stores().withBodies(bodies),
            clock,
            TestMetrics.collection(),
            new Progress(clock));
  }

  public PageCommitResult completeBody() {
    return commit.commit(claim, RUN, expectedRevision, completedBodyPage());
  }

  public CollectionPage completedBodyPage() {
    var observation =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of(BLOCK), BodyObservation.Outcome.COMPLETE);
    var page =
        new CollectionPage(
            List.of(
                RawItems.item(PAGE).version("v1").order(ORDER).upsert(),
                RawItems.item(BLOCK).resourceType("notion.block").upsert()),
            List.of(),
            new Checkpoint(4, "{}"),
            List.of(observation));
    return page;
  }

  public void previousBodyHasRemovedBlock() {
    rawPage();
    ports
        .rawItems()
        .apply(SOURCE, RawItems.item("block:removed").resourceType("notion.block").upsert());
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(
            FINGERPRINT, PAGE, "v1", ORDER, NOW, Set.of(BLOCK, "block:removed")));
  }

  private void rawPage() {
    ports.rawItems().apply(SOURCE, RawItems.item(PAGE).version("v1").order(ORDER).upsert());
    ports.rawItems().apply(SOURCE, RawItems.item(BLOCK).resourceType("notion.block").upsert());
  }

  public void checkpointWillConflict() {
    expectedRevision = 1;
  }

  public void claimHasExpired() {
    clock.advance(TTL);
  }

  public void claimWasReplacedBeforeExpiry() {
    ports.claims().release(claim, clock.instant());
    var replacement =
        ports
            .claims()
            .tryClaim(SOURCE, new OwnerId("replacement-worker"), TTL, clock.instant())
            .orElseThrow();
    if (replacement.fencingToken() <= claim.fencingToken() || !claim.isActiveAt(clock.instant()))
      throw new IllegalStateException("재점유 각본은 만료 전 fencing token을 교체해야 한다");
  }

  public Optional<RunId> completedRun() {
    return bodies.find(SOURCE, FINGERPRINT, PAGE).flatMap(BodyCompletionStore.Body::completedRun);
  }

  public boolean hasCompletedBody() {
    return bodies.find(SOURCE, FINGERPRINT, PAGE).isPresent();
  }

  public boolean projectionRequested() {
    return ports.outbox().request(SOURCE).isPresent();
  }

  public record StoredEffects(int active, long revision, boolean completed, boolean projection) {}

  public StoredEffects storedEffects() {
    return new StoredEffects(
        ports.rawItems().count(SOURCE),
        ports.checkpoints().read(SOURCE).revision(),
        hasCompletedBody(),
        projectionRequested());
  }

  public boolean removedBlockIsActive() {
    return ports.rawItems().get(SOURCE, "block:removed").orElse(null) instanceof RawRecord.Active;
  }

  public boolean ownsRemovedBlock() {
    return bodies.members(SOURCE, FINGERPRINT, PAGE).contains("block:removed");
  }

  public String completedVersion() {
    return bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().version();
  }
}
