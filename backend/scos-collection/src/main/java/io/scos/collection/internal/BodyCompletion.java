package io.scos.collection.internal;

import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.CollectionState;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 본문 완료와 해당 페이지 소유 블록의 부분 정리를 소유한다. 수집 트랜잭션 안에서 실행한다. */
public final class BodyCompletion {
  private static final int PERCENT_SCALE = 100;
  private final BodyCompletionStore bodies;
  private final RawItemStore raw;
  private final SnapshotShrinkLimits limits;
  private final ReconciliationDiscovery reconciliation;

  public BodyCompletion(BodyCompletionStore bodies, RawItemStore raw, SnapshotShrinkLimits limits) {
    this.bodies = bodies;
    this.raw = raw;
    this.limits = limits;
    this.reconciliation = new ReconciliationDiscovery(bodies, raw);
  }

  public CollectionState state(SourceInstanceId source) {
    return (fingerprint, ids, cancel) -> {
      if (ids.size() > CollectionState.MAX_BATCH)
        throw new IllegalArgumentException("Collection state batch exceeds limit");
      if (cancel.isCancelled())
        throw new CallFailureException(
            "collection.state",
            CallFailure.of(CallFailure.Kind.CANCELLED, "Collection state read cancelled"));
      var summaries = new HashMap<String, CollectionState.BodySummary>();
      for (String id : ids) {
        var completed = bodies.find(source, fingerprint, id);
        var pending = bodies.pending(source, fingerprint, id);
        if (completed.isEmpty() && pending.isEmpty()) continue;
        summaries.put(
            id,
            new CollectionState.BodySummary(
                raw.get(source, id).orElse(null) instanceof RawRecord.Active,
                completed.map(
                    body ->
                        new CollectionState.Completion(
                            body.version(), body.order(), body.completedAt())),
                pending.isPresent(),
                true));
      }
      return Map.copyOf(summaries);
    };
  }

  public List<String> apply(SourceInstanceId source, RunId run, CollectionPage page, Instant at) {
    var removed = new ArrayList<String>();
    for (BodyObservation observation : page.bodies())
      removed.addAll(apply(source, run, observation, page.mutations(), at));
    return List.copyOf(removed);
  }

  private List<String> apply(
      SourceInstanceId source,
      RunId run,
      BodyObservation observation,
      List<RawMutation> facts,
      Instant at) {
    if (observation.outcome() == BodyObservation.Outcome.DELETED)
      return deleteBody(source, run, observation, at);
    if (!(raw.get(source, observation.pageId()).orElse(null) instanceof RawRecord.Active active)
        || !active.item().sourceVersion().equals(observation.version())) return List.of();
    active
        .item()
        .sourceOrder()
        .ifPresent(
            order -> {
              if (!order.scheme().equals(observation.order().scheme()))
                throw new IllegalArgumentException("Body completion order scheme conflict");
            });
    var previous = bodies.find(source, observation.fingerprint(), observation.pageId());
    if (previous.isPresent()
        && observation.order().position().compareTo(previous.get().order().position()) < 0)
      return List.of();
    if (observation.outcome() == BodyObservation.Outcome.CONFIRMED) {
      previous
          .filter(body -> body.version().equals(observation.version()))
          .filter(
              body ->
                  bodies.pending(source, observation.fingerprint(), observation.pageId()).isEmpty())
          .ifPresent(
              body ->
                  observation
                      .reconciliation()
                      .ifPresent(context -> bodies.save(source, body.readAt(context.path(), at))));
      return List.of();
    }
    var observed = recordPending(source, run, observation, facts);
    if (observation.outcome() == BodyObservation.Outcome.ENUMERATING) return List.of();
    return completeBody(source, run, observation, observed, at);
  }

  private List<String> deleteBody(
      SourceInstanceId source, RunId run, BodyObservation observation, Instant at) {
    var stored = raw.get(source, observation.pageId()).orElse(null);
    if (!(stored instanceof RawRecord.Deleted deleted)
        || deleted.lastSourceVersion().filter(observation.version()::equals).isEmpty()
        || deleted.sourceOrder().filter(observation.order()::equals).isEmpty()) return List.of();
    var exclusive =
        bodies.members(source, observation.fingerprint(), observation.pageId()).stream()
            .filter(
                block ->
                    bodies.owners(source, observation.fingerprint(), block).stream()
                        .allMatch(
                            owner ->
                                owner.equals(observation.pageId())
                                    || raw.get(source, owner).orElse(null)
                                        instanceof RawRecord.Deleted))
            .collect(Collectors.toSet());
    var removed = raw.tombstoneSelected(source, exclusive, at);
    bodies.save(
        source,
        new BodyCompletionStore.Body(
            observation.fingerprint(),
            observation.pageId(),
            observation.version(),
            observation.order(),
            at,
            Set.of(),
            Optional.of(run)));
    return removed;
  }

  private Set<String> recordPending(
      SourceInstanceId source, RunId run, BodyObservation observation, List<RawMutation> facts) {
    var observed = new HashSet<>(observation.blocks());
    boolean freshRead =
        observation.outcome() == BodyObservation.Outcome.ENUMERATING
            && facts.stream()
                .anyMatch(
                    m ->
                        m instanceof RawMutation.Upsert
                            && m.externalId().equals(observation.pageId()));
    if (!freshRead)
      bodies
          .pending(source, observation.fingerprint(), observation.pageId())
          .filter(p -> p.run().equals(run) && p.version().equals(observation.version()))
          .ifPresent(p -> observed.addAll(p.observed()));
    bodies.savePending(
        source,
        new BodyCompletionStore.Pending(
            observation.fingerprint(),
            observation.pageId(),
            observation.version(),
            observation.order(),
            run,
            observed));
    return observed;
  }

  private List<String> completeBody(
      SourceInstanceId source,
      RunId run,
      BodyObservation observation,
      Set<String> observed,
      Instant at) {
    var owned =
        bodies.members(source, observation.fingerprint(), observation.pageId()).stream()
            .filter(id -> raw.get(source, id).orElse(null) instanceof RawRecord.Active)
            .collect(Collectors.toSet());
    var absent = new HashSet<>(owned);
    absent.removeAll(observed);
    absent.removeIf(
        block ->
            bodies.owners(source, observation.fingerprint(), block).stream()
                .anyMatch(
                    owner ->
                        !owner.equals(observation.pageId())
                            && !(raw.get(source, owner).orElse(null)
                                instanceof RawRecord.Deleted)));
    if (owned.size() >= limits.minActive()
        && (long) absent.size() * PERCENT_SCALE > (long) limits.maxDeletePercent() * owned.size())
      return List.of();
    var removed = raw.tombstoneSelected(source, absent, at);
    var completed =
        new BodyCompletionStore.Body(
            observation.fingerprint(),
            observation.pageId(),
            observation.version(),
            observation.order(),
            at,
            observed,
            Optional.of(run));
    bodies.save(
        source,
        observation
            .reconciliation()
            .map(context -> completed.readAt(context.path(), at))
            .orElse(completed));
    reconciliation.bodyCompleted(source, run, observation);
    return removed;
  }
}
