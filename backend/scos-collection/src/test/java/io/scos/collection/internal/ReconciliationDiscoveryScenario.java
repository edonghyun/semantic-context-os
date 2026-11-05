package io.scos.collection.internal;

import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.ReconciliationContext;
import io.scos.spi.connector.ReconciliationKind;
import io.scos.spi.connector.ReconciliationObservation;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 같은 실제 페이지 커밋으로 원본·본문 완료·집계를 관찰한다. 메모리에서는 TX를 주장하지 않는다. */
public final class ReconciliationDiscoveryScenario {
  static final SourceInstanceId SOURCE = new SourceInstanceId("reconciliation-source");
  static final RunId RUN = new RunId("reconciliation-run");
  static final String FINGERPRINT = "roots-generation";
  static final String PAGE = "page";
  static final String BLOCK = "block:one";
  static final String UNCHANGED_PAGE = "unchanged-page";
  static final String UNCHANGED_BLOCK = "block:unchanged";
  static final String OLD_VERSION = "v0";
  static final String NEW_VERSION = "v1";
  static final SourceOrder OLD_ORDER = new SourceOrder("notion.page.v1", "0000");
  static final SourceOrder NEWER_ORDER = new SourceOrder("notion.page.v1", "0002");
  static final String NEWER_VERSION = "v2";
  static final SourceOrder NEW_ORDER = new SourceOrder("notion.page.v1", "0001");
  static final Instant LAST_SCAN = Instant.parse("2026-10-25T01:00:00Z");
  static final Instant GRACE_BOUNDARY = Instant.parse("2026-10-25T00:30:00Z");
  static final Instant NOW = Instant.parse("2026-10-25T01:05:00Z");
  static final Duration LEASE_TTL = Duration.ofHours(1);
  final CommitContext.Ports ports;
  final BodyCompletionStore bodies;
  final MutableClock clock = new MutableClock(NOW);
  final WorkClaim claim;
  final PageCommit commits;
  long revision;
  final List<RawMutation> additionalMutations = new ArrayList<>();
  final List<BodyObservation> additionalBodies = new ArrayList<>();
  final List<ReconciliationObservation> stateReads = new ArrayList<>();
  Optional<Instant> editedAt = Optional.of(GRACE_BOUNDARY);
  Optional<Instant> successfulExploration = Optional.of(LAST_SCAN);
  String pageVersion = NEW_VERSION;
  boolean bootstrap;
  Duration gracePeriod = Duration.ofMinutes(30);
  ReconciliationContext.Path path = ReconciliationContext.Path.FULL;
  RunOutcome outcome = RunOutcome.succeeded(0);
  CollectionCoverage coverage = CollectionCoverage.COMPLETE_SNAPSHOT;

  public ReconciliationDiscoveryScenario(CommitContext.Ports ports, BodyCompletionStore bodies) {
    this.ports = ports;
    this.bodies = bodies;
    ports.sources().save(SourceInstances.noop(SOURCE, NOW));
    ports
        .rawItems()
        .apply(
            SOURCE,
            RawItems.item(PAGE)
                .resourceType("notion.page")
                .version(OLD_VERSION)
                .order(OLD_ORDER)
                .upsert());
    ports
        .rawItems()
        .apply(
            SOURCE,
            RawItems.item(BLOCK).resourceType("notion.block").version(OLD_VERSION).upsert());
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(
            FINGERPRINT, PAGE, OLD_VERSION, OLD_ORDER, NOW, Set.of(BLOCK)));
    claim =
        ports
            .claims()
            .tryClaim(SOURCE, new OwnerId("reconciliation-worker"), LEASE_TTL, NOW)
            .orElseThrow();
    ports.runs().start(RUN, SOURCE, NOW);
    commits =
        new PageCommit(
            ports.stores().withBodies(bodies),
            clock,
            TestMetrics.collection(),
            new Progress(clock));
  }

  void overlapIsAnHour() {
    gracePeriod = Duration.ofHours(1);
  }

  void overlapIsTenMinutes() {
    gracePeriod = Duration.ofMinutes(10);
    editedAt = Optional.of(Instant.parse("2026-10-25T00:40:00Z"));
  }

  void pageMetadataAdvancedPastCompletedBody() {
    stateWasAlreadyCommitted();
    ports
        .rawItems()
        .apply(
            SOURCE,
            RawItems.item(PAGE)
                .resourceType("notion.page")
                .version(NEWER_VERSION)
                .order(NEWER_ORDER)
                .upsert());
  }

  void staleTrashAlsoCompared() {
    unchangedTrashAlsoCompared();
    ports
        .rawItems()
        .apply(
            SOURCE,
            new RawMutation.Delete(
                UNCHANGED_PAGE,
                Optional.of("trashed:" + NEWER_VERSION),
                Optional.of(NEWER_ORDER),
                Optional.of(LAST_SCAN),
                NOW));
  }

  void unchangedTrashAlsoCompared() {
    ports
        .rawItems()
        .apply(
            SOURCE,
            RawItems.item(UNCHANGED_PAGE)
                .resourceType("notion.page")
                .version(OLD_VERSION)
                .order(OLD_ORDER)
                .upsert());
    var deleted =
        new RawMutation.Delete(
            UNCHANGED_PAGE,
            Optional.of("trashed:" + NEW_VERSION),
            Optional.of(NEW_ORDER),
            Optional.of(GRACE_BOUNDARY),
            NOW);
    ports.rawItems().apply(SOURCE, deleted);
    additionalMutations.add(deleted);
    stateReads.add(
        new ReconciliationObservation(
            FINGERPRINT,
            UNCHANGED_PAGE,
            ReconciliationKind.PAGE,
            ReconciliationContexts.withDefaultGrace(
                ReconciliationContext.Path.FULL, successfulExploration, editedAt, false)));
  }

  static final String CONTAINER = "database";

  void changedContainerRead() {
    ports
        .rawItems()
        .apply(
            SOURCE,
            RawItems.item(CONTAINER)
                .resourceType("notion.database")
                .version(OLD_VERSION)
                .order(OLD_ORDER)
                .upsert());
    additionalMutations.add(
        RawItems.item(CONTAINER)
            .resourceType("notion.database")
            .version(NEW_VERSION)
            .order(NEW_ORDER)
            .upsert());
    stateReads.add(
        new ReconciliationObservation(
            FINGERPRINT,
            CONTAINER,
            ReconciliationKind.CONTAINER,
            ReconciliationContexts.withDefaultGrace(
                ReconciliationContext.Path.FULL, successfulExploration, editedAt, false)));
  }

  BodyCompletionStore.Body completedBody() {
    return bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow();
  }

  void confirmFromNextIncrementalRun() {
    var nextRun = new RunId("confirmation-run");
    ports.runs().finish(RUN, outcome, coverage, NOW, 0, AccessResult.SKIPPED, Optional.empty());
    ports.runs().start(nextRun, SOURCE, NOW);
    var body =
        new BodyObservation(
            FINGERPRINT,
            PAGE,
            NEW_VERSION,
            NEW_ORDER,
            Set.of(),
            BodyObservation.Outcome.CONFIRMED,
            Optional.of(
                ReconciliationContexts.withDefaultGrace(
                    ReconciliationContext.Path.INCREMENTAL,
                    successfulExploration,
                    editedAt,
                    false)));
    var page = new CollectionPage(List.of(), List.of(), new Checkpoint(1, "{}"), List.of(body));
    revision = commits.commit(claim, nextRun, revision, page).revision();
  }

  int discoveryCount() {
    return bodies.discoveries(SOURCE, RUN).size();
  }

  void difference(ReconciliationDiscovery.Classification classification) {
    switch (classification) {
      case INCREMENTAL_MISS -> {}
      case RECENT -> recentEdit();
      case FULL_ONLY -> samePageVersionWithChangedBody();
      case BOOTSTRAP -> generationIsBootstrapping();
      case UNKNOWN -> unknown(UnknownEvidence.EDIT_TIME);
    }
  }

  void incrementalFinishesFullBody() {
    commitPage(BodyObservation.Outcome.ENUMERATING, ReconciliationContext.Path.FULL);
    path = ReconciliationContext.Path.INCREMENTAL;
  }

  void stateWasAlreadyCurrent() {
    ports
        .rawItems()
        .apply(
            SOURCE,
            RawItems.item(PAGE)
                .resourceType("notion.page")
                .version(NEW_VERSION)
                .order(NEW_ORDER)
                .upsert());
    ports
        .rawItems()
        .apply(
            SOURCE,
            RawItems.item(BLOCK).resourceType("notion.block").version(NEW_VERSION).upsert());
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(
            FINGERPRINT, PAGE, NEW_VERSION, NEW_ORDER, NOW, Set.of(BLOCK)));
  }

  void unchangedPageAlsoCompared() {
    var page =
        RawItems.item(UNCHANGED_PAGE)
            .resourceType("notion.page")
            .version(NEW_VERSION)
            .order(NEW_ORDER)
            .upsert();
    var block =
        RawItems.item(UNCHANGED_BLOCK).resourceType("notion.block").version(NEW_VERSION).upsert();
    ports.rawItems().apply(SOURCE, page);
    ports.rawItems().apply(SOURCE, block);
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(
            FINGERPRINT, UNCHANGED_PAGE, NEW_VERSION, NEW_ORDER, NOW, Set.of(UNCHANGED_BLOCK)));
    additionalMutations.addAll(List.of(page, block));
    additionalBodies.add(
        new BodyObservation(
            FINGERPRINT,
            UNCHANGED_PAGE,
            NEW_VERSION,
            NEW_ORDER,
            Set.of(UNCHANGED_BLOCK),
            BodyObservation.Outcome.COMPLETE,
            Optional.of(
                ReconciliationContexts.withDefaultGrace(
                    ReconciliationContext.Path.FULL, successfulExploration, editedAt, bootstrap))));
  }

  enum PartialRun {
    REJECTED,
    RESUMED,
    FAILED,
    DEFERRED
  }

  void partialRun(PartialRun run) {
    switch (run) {
      case REJECTED -> outcome = RunOutcome.succeeded(1);
      case RESUMED -> coverage = CollectionCoverage.INCREMENTAL;
      case FAILED ->
          outcome =
              RunOutcome.failed(
                  RunStatus.PARTIALLY_SUCCEEDED, "dependency_unavailable", "synthetic failure");
      case DEFERRED ->
          outcome =
              RunOutcome.succeeded(0)
                  .markedAs("absence_verification_deferred", "synthetic deferral");
    }
  }

  enum UnknownEvidence {
    EDIT_TIME,
    EXPLORATION,
    BEFORE_STATE
  }

  void unknown(UnknownEvidence evidence) {
    switch (evidence) {
      case EDIT_TIME -> editedAt = Optional.empty();
      case EXPLORATION -> successfulExploration = Optional.empty();
      case BEFORE_STATE ->
          bodies.savePending(
              SOURCE,
              new BodyCompletionStore.Pending(
                  FINGERPRINT,
                  PAGE,
                  OLD_VERSION,
                  OLD_ORDER,
                  new RunId("previous-run"),
                  Set.of(BLOCK)));
    }
  }

  void stateWasAlreadyCommitted() {
    commitPage(BodyObservation.Outcome.COMPLETE, ReconciliationContext.Path.FULL);
  }

  void generationIsBootstrapping() {
    bootstrap = true;
  }

  void samePageVersionWithChangedBody() {
    pageVersion = OLD_VERSION;
  }

  void recentEdit() {
    editedAt = Optional.of(LAST_SCAN.plusSeconds(1));
  }

  List<String> classifications() {
    return bodies.discoveries(SOURCE, RUN).stream()
        .flatMap(discovery -> discovery.classification().stream())
        .map(Enum::name)
        .toList();
  }

  enum BodyFact {
    NONE,
    OTHER_PAGE,
    DELETE
  }

  void compareWithoutPageUpsert(BodyFact fact) {
    List<RawMutation> mutations =
        switch (fact) {
          case NONE -> List.of();
          case OTHER_PAGE -> List.of(RawItems.item("unrelated").upsert());
          case DELETE ->
              List.of(
                  new RawMutation.Delete(
                      PAGE, Optional.empty(), Optional.empty(), Optional.empty(), NOW));
        };
    var body =
        new BodyObservation(
            FINGERPRINT,
            PAGE,
            NEW_VERSION,
            NEW_ORDER,
            Set.of(BLOCK),
            BodyObservation.Outcome.ENUMERATING,
            Optional.of(
                ReconciliationContexts.withDefaultGrace(
                    ReconciliationContext.Path.FULL, successfulExploration, editedAt, false)));
    new ReconciliationDiscovery(bodies, ports.rawItems())
        .beforeApply(
            SOURCE,
            RUN,
            new CollectionPage(mutations, List.of(), new Checkpoint(1, "{}"), List.of(body)));
  }

  void compareIncrementalBody() {
    var context =
        ReconciliationContexts.withDefaultGrace(
            ReconciliationContext.Path.INCREMENTAL, successfulExploration, editedAt, false);
    var body =
        new BodyObservation(
            FINGERPRINT,
            PAGE,
            NEW_VERSION,
            NEW_ORDER,
            Set.of(BLOCK),
            BodyObservation.Outcome.ENUMERATING,
            Optional.of(context));
    new ReconciliationDiscovery(bodies, ports.rawItems())
        .beforeApply(
            SOURCE,
            RUN,
            new CollectionPage(
                List.of(RawItems.item(PAGE).version(NEW_VERSION).order(NEW_ORDER).upsert()),
                List.of(),
                new Checkpoint(1, "{}"),
                List.of(body)));
  }

  List<ReconciliationDiscovery.ResourceComparison> incrementalResourceComparisons() {
    var context =
        ReconciliationContexts.withDefaultGrace(
            ReconciliationContext.Path.INCREMENTAL, successfulExploration, editedAt, false);
    var read =
        new ReconciliationObservation(
            FINGERPRINT, "container", ReconciliationKind.CONTAINER, context);
    return new ReconciliationDiscovery(bodies, ports.rawItems())
        .beforeApply(
            SOURCE,
            RUN,
            new CollectionPage(
                List.of(),
                List.of(),
                new Checkpoint(1, "{}"),
                List.of(),
                Optional.empty(),
                List.of(read)));
  }

  boolean hasComparison() {
    return bodies.comparison(SOURCE, RUN, FINGERPRINT, PAGE).isPresent();
  }

  void completedVersionDoesNotMatchRaw() {
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(
            FINGERPRINT, PAGE, "unrelated-version", OLD_ORDER, NOW, Set.of(BLOCK)));
  }

  public void reconcile() {
    commitPage(BodyObservation.Outcome.COMPLETE, path);
    ports.runs().finish(RUN, outcome, coverage, NOW, 0, AccessResult.SKIPPED, Optional.empty());
  }

  private void commitPage(BodyObservation.Outcome outcome, ReconciliationContext.Path path) {
    var context =
        new ReconciliationContext(path, successfulExploration, editedAt, bootstrap, gracePeriod);
    var mutations =
        new ArrayList<RawMutation>(
            List.of(
                RawItems.item(PAGE)
                    .resourceType("notion.page")
                    .version(pageVersion)
                    .order(NEW_ORDER)
                    .upsert(),
                RawItems.item(BLOCK).resourceType("notion.block").version(NEW_VERSION).upsert()));
    mutations.addAll(additionalMutations);
    var observations =
        new ArrayList<>(
            List.of(
                new BodyObservation(
                    FINGERPRINT,
                    PAGE,
                    pageVersion,
                    NEW_ORDER,
                    Set.of(BLOCK),
                    outcome,
                    Optional.of(context))));
    observations.addAll(additionalBodies);
    var page =
        new CollectionPage(
            mutations,
            List.of(),
            new Checkpoint(1, "{}"),
            observations,
            Optional.empty(),
            stateReads);
    revision = commits.commit(claim, RUN, revision, page).revision();
  }

  public void checkpointWillConflict() {
    ports.checkpoints().advance(SOURCE, revision, new Checkpoint(1, "{}"), NOW);
  }

  public record Effects(
      String pageVersion,
      String blockVersion,
      String completedVersion,
      long checkpointRevision,
      int discoveries,
      boolean comparing,
      Set<String> observed) {}

  public Effects effects() {
    return new Effects(
        ports.rawItems().get(SOURCE, PAGE).flatMap(RawRecord::lastSourceVersion).orElseThrow(),
        ports.rawItems().get(SOURCE, BLOCK).flatMap(RawRecord::lastSourceVersion).orElseThrow(),
        bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().version(),
        ports.checkpoints().read(SOURCE).revision(),
        bodies.discoveries(SOURCE, RUN).size(),
        bodies.comparison(SOURCE, RUN, FINGERPRINT, PAGE).isPresent(),
        ports.runs().observations(RUN));
  }

  boolean hasRecordedSample() {
    return new ReconciliationDiscovery(bodies, ports.rawItems())
        .report(ports.runs().get(RUN).orElseThrow())
        .isPresent();
  }

  ReconciliationDiscovery.Summary summary() {
    return new ReconciliationDiscovery(bodies, ports.rawItems())
        .report(ports.runs().get(RUN).orElseThrow())
        .orElseThrow();
  }
}
