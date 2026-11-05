package io.scos.collection.internal;

import io.scos.engine.support.text.Text;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.ReconciliationContext;
import io.scos.spi.connector.ReconciliationKind;
import io.scos.spi.connector.ReconciliationObservation;
import io.scos.spi.connector.SnapshotScope;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** 검증된 전체 본문의 전후 상태를 비교하고 영속 발견·분모 보고를 복원한다. */
public final class ReconciliationDiscovery {
  public enum Classification {
    INCREMENTAL_MISS,
    RECENT,
    FULL_ONLY,
    BOOTSTRAP,
    UNKNOWN
  }

  public record Discovery(
      String fingerprint,
      String resourceId,
      String stateKey,
      ReconciliationKind kind,
      Optional<Classification> classification,
      boolean eligible) {}

  public record Comparison(
      String fingerprint,
      String pageId,
      Optional<String> previousState,
      Optional<String> previousVersion,
      Optional<Instant> successfulExploration,
      boolean bootstrap) {
    Discovery comparedWith(
        String version, ReconciliationContext context, String state, ReconciliationKind kind) {
      var editedAt = context.editedAt();
      boolean eligible =
          context.path() == ReconciliationContext.Path.FULL
              && previousState.isPresent()
              && !bootstrap
              && successfulExploration.isPresent()
              && editedAt.isPresent()
              && !editedAt.get().isAfter(successfulExploration.get().minus(context.gracePeriod()));
      Optional<Classification> classification = Optional.empty();
      if (previousState.filter(state::equals).isEmpty()) {
        if (bootstrap) classification = Optional.of(Classification.BOOTSTRAP);
        else if (previousState.isEmpty()) classification = Optional.of(Classification.UNKNOWN);
        else if (previousVersion.filter(version::equals).isPresent())
          classification = Optional.of(Classification.FULL_ONLY);
        else if (context.path() == ReconciliationContext.Path.INCREMENTAL)
          classification = Optional.of(Classification.RECENT);
        else if (successfulExploration.isEmpty() || editedAt.isEmpty())
          classification = Optional.of(Classification.UNKNOWN);
        else
          classification =
              Optional.of(eligible ? Classification.INCREMENTAL_MISS : Classification.RECENT);
      }
      return new Discovery(
          fingerprint,
          pageId,
          state,
          kind,
          classification,
          kind == ReconciliationKind.PAGE
              && eligible
              && classification.filter(value -> value == Classification.FULL_ONLY).isEmpty());
    }
  }

  public record Count(ReconciliationKind kind, Classification classification, long states) {}

  public record Summary(long eligiblePages, boolean partial, List<Count> discovered) {
    public Summary {
      discovered = List.copyOf(discovered);
    }

    public long missedPages() {
      return count(ReconciliationKind.PAGE, Classification.INCREMENTAL_MISS);
    }

    public long count(ReconciliationKind kind, Classification classification) {
      return discovered.stream()
          .filter(count -> count.kind() == kind && count.classification() == classification)
          .mapToLong(Count::states)
          .sum();
    }

    public OptionalDouble missRatio() {
      return eligiblePages == 0
          ? OptionalDouble.empty()
          : OptionalDouble.of((double) missedPages() / eligiblePages);
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private final BodyCompletionStore bodies;
  private final RawItemStore raw;

  public ReconciliationDiscovery(BodyCompletionStore bodies, RawItemStore raw) {
    this.bodies = bodies;
    this.raw = raw;
  }

  record ResourceComparison(
      ReconciliationObservation observation, Comparison before, Optional<String> observedVersion) {}

  List<ResourceComparison> beforeApply(SourceInstanceId source, RunId run, CollectionPage page) {
    for (var body : page.bodies()) {
      if (body.reconciliation()
          .filter(context -> context.path() == ReconciliationContext.Path.FULL)
          .isEmpty()) continue;
      if (page.mutations().stream()
          .noneMatch(
              mutation ->
                  mutation instanceof RawMutation.Upsert
                      && mutation.externalId().equals(body.pageId()))) continue;
      if (bodies.comparison(source, run, body.fingerprint(), body.pageId()).isPresent()) continue;
      var context = body.reconciliation().orElseThrow();
      bodies.beginComparison(
          source,
          run,
          new Comparison(
              body.fingerprint(),
              body.pageId(),
              comparisonState(source, body),
              raw.get(source, body.pageId()).flatMap(RawRecord::lastSourceVersion),
              context.successfulExploration(),
              context.bootstrap()));
    }
    return page.reconciliations().stream()
        .filter(read -> read.context().path() == ReconciliationContext.Path.FULL)
        .map(read -> beforeResource(source, page, read))
        .toList();
  }

  private ResourceComparison beforeResource(
      SourceInstanceId source, CollectionPage page, ReconciliationObservation read) {
    return new ResourceComparison(
        read,
        new Comparison(
            read.fingerprint(),
            read.resourceId(),
            raw.get(source, read.resourceId())
                .map(previous -> resourceKey(source, read.resourceId())),
            raw.get(source, read.resourceId()).flatMap(RawRecord::lastSourceVersion),
            read.context().successfulExploration(),
            read.context().bootstrap()),
        page.mutations().stream()
            .filter(mutation -> mutation.externalId().equals(read.resourceId()))
            .findFirst()
            .flatMap(
                mutation ->
                    switch (mutation) {
                      case RawMutation.Upsert upsert -> Optional.of(upsert.item().sourceVersion());
                      case RawMutation.Delete deleted -> deleted.sourceVersion();
                    }));
  }

  void bodyCompleted(SourceInstanceId source, RunId run, BodyObservation body) {
    if (body.reconciliation().isEmpty()) return;
    bodies
        .comparison(source, run, body.fingerprint(), body.pageId())
        .ifPresent(
            before ->
                bodies.recordDiscovery(
                    source,
                    run,
                    before.comparedWith(
                        body.version(),
                        body.reconciliation().orElseThrow(),
                        stateKey(source, body.fingerprint(), body.pageId()),
                        ReconciliationKind.PAGE)));
  }

  void resourcesApplied(SourceInstanceId source, RunId run, List<ResourceComparison> comparisons) {
    for (var comparison : comparisons) {
      var read = comparison.observation();
      String state = resourceKey(source, read.resourceId());
      if (read.kind() != ReconciliationKind.PAGE
          && comparison.before().previousState().filter(state::equals).isPresent()) continue;
      var version = raw.get(source, read.resourceId()).flatMap(RawRecord::lastSourceVersion);
      if (comparison.observedVersion().isEmpty() || !comparison.observedVersion().equals(version))
        continue;
      bodies.recordDiscovery(
          source,
          run,
          comparison
              .before()
              .comparedWith(version.orElseThrow(), read.context(), state, read.kind()));
    }
  }

  private String resourceKey(SourceInstanceId source, String id) {
    return Text.sha256Hex(resourceState(source, id));
  }

  void absencesApplied(
      SourceInstanceId source, RunId run, SnapshotScope scope, List<String> absent) {
    if (scope.reconciliationKinds().isEmpty()) return;
    var changed = new TreeMap<String, ReconciliationKind>();
    for (String id : absent) {
      var kind =
          raw.get(source, id)
              .flatMap(RawRecord::resourceType)
              .map(scope.reconciliationKinds()::get);
      if (kind.isPresent()) changed.put(id, kind.get());
      else
        for (String owner : bodies.owners(source, scope.fingerprint(), id))
          changed.put(owner, ReconciliationKind.PAGE);
    }
    changed.forEach(
        (id, kind) ->
            bodies.recordDiscovery(
                source,
                run,
                new Discovery(
                    scope.fingerprint(),
                    id,
                    kind == ReconciliationKind.PAGE
                        ? stateKey(source, scope.fingerprint(), id)
                        : resourceKey(source, id),
                    kind,
                    Optional.of(Classification.FULL_ONLY),
                    false)));
  }

  public Optional<Summary> report(CollectionRun run) {
    var discoveries = bodies.discoveries(run.source(), run.id());
    if (discoveries.isEmpty()) return Optional.empty();
    long eligible =
        discoveries.stream()
            .filter(
                discovery -> discovery.kind() == ReconciliationKind.PAGE && discovery.eligible())
            .count();
    boolean partial =
        run.status() != RunStatus.SUCCEEDED
            || run.coverage()
                .filter(value -> value == CollectionCoverage.COMPLETE_SNAPSHOT)
                .isEmpty()
            || run.errorKind().isPresent();
    var counts = new ArrayList<Count>();
    for (ReconciliationKind kind : ReconciliationKind.values()) {
      for (Classification classification : Classification.values()) {
        long states =
            discoveries.stream()
                .filter(
                    discovery ->
                        discovery.kind() == kind
                            && discovery
                                .classification()
                                .filter(value -> value == classification)
                                .isPresent())
                .count();
        if (states > 0) counts.add(new Count(kind, classification, states));
      }
    }
    return Optional.of(new Summary(eligible, partial, counts));
  }

  private Optional<String> comparisonState(SourceInstanceId source, BodyObservation body) {
    var previous = raw.get(source, body.pageId()).orElse(null);
    if (previous instanceof RawRecord.Active active
        && (bodies.pending(source, body.fingerprint(), body.pageId()).isPresent()
            || bodies
                .find(source, body.fingerprint(), body.pageId())
                .filter(completed -> completed.version().equals(active.item().sourceVersion()))
                .isEmpty())) return Optional.empty();
    return Optional.of(stateKey(source, body.fingerprint(), body.pageId()));
  }

  private String stateKey(SourceInstanceId source, String fingerprint, String pageId) {
    var states = new TreeMap<String, String>();
    states.put(pageId, resourceState(source, pageId));
    for (String block : bodies.members(source, fingerprint, pageId))
      states.put(block, resourceState(source, block));
    return Text.sha256Hex(JSON.valueToTree(states).toString());
  }

  private String resourceState(SourceInstanceId source, String id) {
    return raw.get(source, id)
        .map(
            record ->
                (record instanceof RawRecord.Active ? "active:" : "deleted:")
                    + record.lastSourceVersion().orElse(""))
        .orElse("absent");
  }
}
