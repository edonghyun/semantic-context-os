package io.scos.collection.internal;

import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.AbsenceVerification;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.CollectionState;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.ReconciliationContext;
import io.scos.spi.connector.ReconciliationKind;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SnapshotScope;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 실제 포트의 관측·후보·완료를 한 run에 묶는다. */
public final class AbsenceVerificationScenario {
  static final RunId RESUMED_RUN = new RunId("resumed-absence-run");
  // 기본 run에서 outside와 unknown 두 페이지가 미관측이다.
  static final int INITIAL_MISSING_PAGE_COUNT = 2;
  static final RunId RUN = new RunId("absence-verification-run");
  static final String DELETED_OWNER = "deleted-owner";
  static final String PAGE_TYPE = "notion.page";
  static final Duration TTL = Duration.ofMinutes(1);
  final CommitContext.Ports ports;
  final BodyCompletionStore bodies;
  final MutableClock clock = new MutableClock(BodyCommitScenario.NOW);
  final WorkClaim claim;
  final SourceDescriptor source;
  final SnapshotScope scope =
      new SnapshotScope(
          BodyCommitScenario.FINGERPRINT,
          Map.of(
              PAGE_TYPE,
              ReconciliationKind.PAGE,
              "notion.database",
              ReconciliationKind.CONTAINER,
              "notion.data_source",
              ReconciliationKind.CONTAINER));
  final RunCompletion completion;
  final PageCommit pageCommit;

  public AbsenceVerificationScenario(CommitContext.Ports ports, BodyCompletionStore bodies) {
    this.ports = ports;
    this.bodies = bodies;
    var registered = SourceInstances.noop(BodyCommitScenario.SOURCE, clock.instant());
    ports.sources().save(registered);
    source = registered.descriptor();
    for (String id : List.of("seen", "outside", "unknown"))
      ports.rawItems().apply(source.id(), RawItems.item(id).resourceType(PAGE_TYPE).upsert());
    claim =
        ports
            .claims()
            .tryClaim(source.id(), new OwnerId("absence-worker"), TTL, clock.instant())
            .orElseThrow();
    ports.runs().start(RUN, source.id(), clock.instant());
    ports.runs().observe(RUN, List.of("seen"));
    ports.runs().requireAbsenceVerification(RUN, scope.fingerprint());
    completion =
        new RunCompletion(
            ports.stores().withBodies(bodies),
            new SnapshotShrinkGuard(
                ports.sources(),
                ports.guards(),
                SnapshotShrinkLimits.DEFAULTS,
                TestMetrics.collection()));
    completion.recordSnapshotBaseline(source.id(), RUN);
    pageCommit =
        new PageCommit(
            ports.stores().withBodies(bodies),
            clock,
            TestMetrics.collection(),
            new Progress(clock));
  }

  static final String INCREMENTAL_PAGE = "incremental-page";
  // 기본 미관측 페이지 둘과 부분 정리 블록 하나다.
  static final int PARTIAL_AND_FULL_DELETIONS = 3;

  public void incrementalPageCommitted() {
    var mutation =
        RawItems.item(INCREMENTAL_PAGE)
            .resourceType(PAGE_TYPE)
            .order(BodyCommitScenario.ORDER)
            .upsert();
    var body =
        new BodyObservation(
            scope.fingerprint(),
            INCREMENTAL_PAGE,
            "v1",
            BodyCommitScenario.ORDER,
            Set.of(),
            BodyObservation.Outcome.COMPLETE,
            Optional.of(
                ReconciliationContexts.withDefaultGrace(
                    ReconciliationContext.Path.INCREMENTAL,
                    Optional.empty(),
                    Optional.empty(),
                    false)));
    pageCommit.commit(
        claim,
        RUN,
        0,
        new CollectionPage(List.of(mutation), List.of(), new Checkpoint(4, "{}"), List.of(body)));
  }

  public void observedBlockWasPartiallyCleaned() {
    blockHasKnownOwner();
    ports.runs().observe(RUN, List.of("block:owned"));
    var body =
        new BodyObservation(
            scope.fingerprint(),
            "seen",
            "v1",
            BodyCommitScenario.ORDER,
            Set.of(),
            BodyObservation.Outcome.COMPLETE);
    pageCommit.commit(
        claim,
        RUN,
        0,
        new CollectionPage(
            List.of(RawItems.item("seen").resourceType(PAGE_TYPE).upsert()),
            List.of(),
            new Checkpoint(4, "{}"),
            List.of(body)));
  }

  public boolean ownedBlockIsDeleted() {
    return ports.rawItems().get(source.id(), "block:owned").orElseThrow()
        instanceof RawRecord.Deleted;
  }

  public void newPagesWouldHideOldInventoryLoss() {
    int baseline = SnapshotShrinkLimits.DEFAULTS.minActive();
    for (int index = ports.rawItems().count(source.id()); index < baseline; index++)
      ports
          .rawItems()
          .apply(source.id(), RawItems.item("old-" + index).resourceType(PAGE_TYPE).upsert());
    completion.recordSnapshotBaseline(source.id(), RUN);
    var added =
        IntStream.range(0, baseline)
            .<RawMutation>mapToObj(
                index -> RawItems.item("new-" + index).resourceType(PAGE_TYPE).upsert())
            .toList();
    pageCommit.commit(claim, RUN, 0, new CollectionPage(added, List.of(), new Checkpoint(4, "{}")));
  }

  public void blockHasKnownOwner() {
    ports
        .rawItems()
        .apply(source.id(), RawItems.item("block:owned").resourceType("notion.block").upsert());
    bodies.save(
        source.id(),
        new BodyCompletionStore.Body(
            scope.fingerprint(),
            "seen",
            "v1",
            BodyCommitScenario.ORDER,
            clock.instant(),
            Set.of("block:owned")));
    completion.recordSnapshotBaseline(source.id(), RUN);
  }

  public void blockHasDeletedOtherOwner() {
    blockHasKnownOwner();
    ports
        .rawItems()
        .apply(source.id(), RawItems.item(DELETED_OWNER).resourceType(PAGE_TYPE).upsert());
    bodies.save(
        source.id(),
        new BodyCompletionStore.Body(
            scope.fingerprint(),
            DELETED_OWNER,
            "v1",
            BodyCommitScenario.ORDER,
            clock.instant(),
            Set.of("block:owned")));
    ports.rawItems().tombstoneSelected(source.id(), Set.of(DELETED_OWNER), clock.instant());
  }

  public void verifyOnlyLiveCandidatesOutside() {
    var selected = candidates();
    var live =
        selected.items().stream()
            .map(item -> item.externalId())
            .filter(id -> !id.equals(DELETED_OWNER))
            .collect(Collectors.toSet());
    ports
        .runs()
        .recordAbsenceValidation(
            RUN, new RunStore.AbsenceValidation(scope.fingerprint(), selected.token(), live));
  }

  public void multipleBlocksHaveOneOwner() {
    blockHasKnownOwner();
    ports
        .rawItems()
        .apply(source.id(), RawItems.item("block:another").resourceType("notion.block").upsert());
    bodies.save(
        source.id(),
        new BodyCompletionStore.Body(
            scope.fingerprint(),
            "seen",
            "v1",
            BodyCommitScenario.ORDER,
            clock.instant(),
            Set.of("block:owned", "block:another")));
    completion.recordSnapshotBaseline(source.id(), RUN);
  }

  public void blockHasTooManyObservedOwners() {
    blockHasKnownOwner();
    for (int number = 0; number < SnapshotScope.MAX_CANDIDATES; number++) {
      String owner = "owner-" + number;
      ports.rawItems().apply(source.id(), RawItems.item(owner).resourceType(PAGE_TYPE).upsert());
      ports.runs().observe(RUN, List.of(owner));
      bodies.save(
          source.id(),
          new BodyCompletionStore.Body(
              scope.fingerprint(),
              owner,
              "v1",
              BodyCommitScenario.ORDER,
              clock.instant(),
              Set.of("block:owned")));
    }
  }

  public void blockHasUnknownOwner() {
    ports
        .rawItems()
        .apply(source.id(), RawItems.item("block:unowned").resourceType("notion.block").upsert());
  }

  public void allRawObservedButBodyIncomplete() {
    ports.runs().observe(RUN, activeIds());
    bodies.savePending(
        source.id(),
        new BodyCompletionStore.Pending(
            scope.fingerprint(), "seen", "v1", BodyCommitScenario.ORDER, RUN, Set.of()));
  }

  public void hasRejectedItem() {
    ports
        .runs()
        .addRejections(
            RUN,
            source.id(),
            clock.instant(),
            List.of(
                new RejectedItem(
                    RejectedItem.FailureKind.UNSUPPORTED_ITEM,
                    "본문 접근 거부",
                    Optional.empty(),
                    clock.instant(),
                    Optional.empty())));
    ports.runs().addPage(RUN, 0, 0, 0, 1);
  }

  public void verifyAllCandidatesOutside() {
    var selected = candidates();
    var exclusions =
        selected.items().stream().map(item -> item.externalId()).collect(Collectors.toSet());
    ports
        .runs()
        .recordAbsenceValidation(
            RUN, new RunStore.AbsenceValidation(scope.fingerprint(), selected.token(), exclusions));
  }

  public void candidateAppears() {
    ports.rawItems().apply(source.id(), RawItems.item("late").resourceType(PAGE_TYPE).upsert());
  }

  public void previousCleanupExists() {
    ports.runs().recordSnapshotCompletion(source.id(), clock.instant().minus(TTL), 0);
  }

  public record CleanupEffects(
      List<String> active,
      Optional<RunStore.SnapshotCompletion> cleanup,
      RunStatus status,
      int discoveries) {}

  public CleanupEffects cleanupEffects() {
    return new CleanupEffects(
        activeIds(),
        lastCleanup(),
        ports.runs().get(RUN).orElseThrow().status(),
        bodies.discoveries(source.id(), RUN).size());
  }

  public long discoveredAbsencePages() {
    return bodies.discoveries(source.id(), RUN).stream()
        .filter(
            discovery ->
                discovery.kind() == ReconciliationKind.PAGE
                    && discovery
                        .classification()
                        .filter(value -> value == ReconciliationDiscovery.Classification.FULL_ONLY)
                        .isPresent())
        .count();
  }

  public boolean hasReconciliationBaseline() {
    return completion
        .state(source.id(), RUN, Optional.of(scope))
        .reconciliationBaseline(scope.fingerprint(), CancellationToken.none());
  }

  public Optional<RunStore.SnapshotCompletion> lastCleanup() {
    return ports.runs().lastSnapshotCompletion(source.id());
  }

  public void claimWasReplacedBeforeExpiry() {
    ports.claims().release(claim, clock.instant());
    var replacement =
        ports
            .claims()
            .tryClaim(source.id(), new OwnerId("replacement-worker"), TTL, clock.instant())
            .orElseThrow();
    if (replacement.fencingToken() <= claim.fencingToken() || !claim.isActiveAt(clock.instant()))
      throw new IllegalStateException("재점유 각본은 만료 전 fencing token을 교체해야 한다");
  }

  public void previousRunObservedMissingPage() {
    ports.runs().observe(RUN, List.of("unknown"));
    ports.runs().start(RESUMED_RUN, source.id(), clock.instant());
    ports.runs().observe(RESUMED_RUN, List.of("seen"));
    ports.runs().requireAbsenceVerification(RESUMED_RUN, scope.fingerprint());
  }

  public CollectionState.AbsenceCandidates resumedCandidates() {
    return completion
        .state(source.id(), RESUMED_RUN, Optional.of(scope))
        .absences(CancellationToken.none());
  }

  public void fillsCandidateLimit() {
    for (int number = 0;
        number < SnapshotScope.MAX_CANDIDATES - INITIAL_MISSING_PAGE_COUNT;
        number++)
      ports
          .rawItems()
          .apply(source.id(), RawItems.item("limit-" + number).resourceType(PAGE_TYPE).upsert());
  }

  public void claimExpires() {
    clock.advance(TTL);
  }

  public void ownerIsTrashedWithoutExclusion() {
    blockHasKnownOwner();
    ports
        .rawItems()
        .apply(
            source.id(),
            new RawMutation.Delete(
                "seen", Optional.empty(), Optional.empty(), Optional.empty(), clock.instant()));
    var selected = candidates();
    ports
        .runs()
        .recordAbsenceValidation(
            RUN,
            new RunStore.AbsenceValidation(
                scope.fingerprint(), selected.token(), Set.of("outside", "unknown")));
  }

  public PageCommitResult commitVerification() {
    var selected = candidates();
    return pageCommit.commit(
        claim,
        RUN,
        0,
        new CollectionPage(
            List.of(RawItems.item("proof-page").resourceType(PAGE_TYPE).upsert()),
            List.of(),
            new Checkpoint(4, "{}"),
            List.of(),
            Optional.of(
                new AbsenceVerification(
                    scope.fingerprint(), selected.token(), Set.of("outside", "unknown")))));
  }

  public record VerificationEffects(
      boolean raw, boolean observed, boolean checkpoint, boolean verification) {}

  public VerificationEffects verificationEffects() {
    return new VerificationEffects(
        ports.rawItems().get(source.id(), "proof-page").isPresent(),
        ports.runs().observations(RUN).contains("proof-page"),
        ports.checkpoints().read(source.id()).checkpoint().isPresent(),
        ports.runs().absenceValidation(RUN).isPresent());
  }

  public void exceedsCandidateLimit() {
    for (int number = 0; number < SnapshotScope.MAX_CANDIDATES; number++)
      ports
          .rawItems()
          .apply(source.id(), RawItems.item("extra-" + number).resourceType(PAGE_TYPE).upsert());
  }

  public CollectionState.AbsenceCandidates candidates() {
    return completion
        .state(source.id(), RUN, Optional.of(scope))
        .absences(CancellationToken.none());
  }

  public CollectionRun complete() {
    return completion.complete(
        new RunCompletion.Input(
            claim,
            RUN,
            RunOutcome.succeeded(0),
            CollectionCoverage.COMPLETE_SNAPSHOT,
            clock.instant(),
            Optional.of(source),
            Optional.of(scope)));
  }

  public List<String> activeIds() {
    return ports
        .rawItems()
        .listActive(source.id(), SnapshotScope.MAX_CANDIDATES, Optional.empty())
        .items()
        .stream()
        .map(item -> item.externalId())
        .toList();
  }
}
