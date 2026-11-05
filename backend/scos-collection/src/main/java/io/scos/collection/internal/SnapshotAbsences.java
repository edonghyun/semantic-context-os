package io.scos.collection.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionState;
import io.scos.spi.connector.CollectionState.AbsenceCandidate;
import io.scos.spi.connector.CollectionState.AbsenceCandidates;
import io.scos.spi.connector.CollectionState.AbsenceStatus;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SnapshotScope;
import io.scos.spi.source.SourceInstanceId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 현재 run의 후보 선정과 저장 상태에 묶인 불투명 검증 token을 소유한다. */
final class SnapshotAbsences {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final RawItemStore raw;
  private final RunStore runs;
  private final Optional<BodyCompletionStore> bodies;

  SnapshotAbsences(RawItemStore raw, RunStore runs, Optional<BodyCompletionStore> bodies) {
    this.raw = raw;
    this.runs = runs;
    this.bodies = bodies;
  }

  AbsenceCandidates candidates(
      SourceInstanceId source, RunId run, SnapshotScope scope, CancellationToken cancel) {
    return select(source, run, scope, cancel).summary();
  }

  Optional<String> verify(SourceInstanceId source, RunId run, SnapshotScope scope) {
    if (runs.absenceRequirement(run).filter(scope.fingerprint()::equals).isEmpty())
      return Optional.of("missing_requirement");
    var verified = runs.absenceValidation(run);
    if (verified.isEmpty()) return Optional.of("missing_validation");
    var validation = verified.get();
    if (!validation.fingerprint().equals(scope.fingerprint())) return Optional.of("scope_mismatch");
    Selection current = select(source, run, scope, CancellationToken.none());
    if (current.summary().status() != AbsenceStatus.AVAILABLE)
      return Optional.of(current.summary().status().name().toLowerCase(Locale.ROOT));
    if (!current.summary().token().equals(validation.candidateToken()))
      return Optional.of("changed_candidates");
    for (var item : current.missing().values()) {
      if (scope.directResourceTypes().contains(item.resourceType())) {
        if (!validation.exclusions().contains(item.externalId()))
          return Optional.of("unverified_candidates");
      } else {
        var owners =
            bodies.orElseThrow().owners(source, scope.fingerprint(), item.externalId()).stream()
                .filter(
                    owner -> !(raw.get(source, owner).orElse(null) instanceof RawRecord.Deleted))
                .collect(Collectors.toSet());
        if (owners.isEmpty() || !validation.exclusions().containsAll(owners))
          return Optional.of("unverified_body");
      }
    }
    return Optional.empty();
  }

  private record Selection(AbsenceCandidates summary, Map<String, RawItem> missing) {}

  private Selection select(
      SourceInstanceId source, RunId run, SnapshotScope scope, CancellationToken cancel) {
    requireActive(cancel);
    if (runs.requireRunning(run, source).rejected() > 0)
      return new Selection(
          new AbsenceCandidates(AbsenceStatus.UNAVAILABLE, "", List.of()), Map.of());
    if (bodies.map(store -> store.hasPending(source, scope.fingerprint(), run)).orElse(false))
      return new Selection(
          new AbsenceCandidates(AbsenceStatus.UNAVAILABLE, "", List.of()), Map.of());
    return new CandidateSelection(source, run, scope).select(cancel);
  }

  private final class CandidateSelection {
    private final SourceInstanceId source;
    private final SnapshotScope scope;
    private final Set<String> observed;
    private final TreeMap<String, RawItem> missing = new TreeMap<>();
    private final TreeMap<String, AbsenceCandidate> targets = new TreeMap<>();
    private final TreeMap<String, RawRecord> ownerItems = new TreeMap<>();
    private final ObjectNode state;
    private boolean unknownOwner;

    CandidateSelection(SourceInstanceId source, RunId run, SnapshotScope scope) {
      this.source = source;
      this.scope = scope;
      this.observed = runs.observations(run);
      state =
          JSON.createObjectNode()
              .put("source", source.value())
              .put("run", run.value())
              .put("fingerprint", scope.fingerprint());
      state.set("types", JSON.valueToTree(scope.directResourceTypes().stream().sorted().toList()));
    }

    Selection select(CancellationToken cancel) {
      readMissing(cancel);
      recordItems();
      if (targets.size() > SnapshotScope.MAX_CANDIDATES)
        return unavailable(AbsenceStatus.LIMIT_EXCEEDED);
      recordOwnership();
      if (targets.size() > SnapshotScope.MAX_CANDIDATES)
        return unavailable(AbsenceStatus.LIMIT_EXCEEDED);
      if (unknownOwner) return unavailable(AbsenceStatus.UNKNOWN_OWNERSHIP);
      recordOwnerStates();
      return new Selection(
          new AbsenceCandidates(
              AbsenceStatus.AVAILABLE,
              Text.sha256Hex(JsJson.stringify(state)),
              List.copyOf(targets.values())),
          Map.copyOf(missing));
    }

    private Selection unavailable(AbsenceStatus status) {
      return new Selection(new AbsenceCandidates(status, "", List.of()), Map.copyOf(missing));
    }

    private void readMissing(CancellationToken cancel) {
      Optional<String> cursor = Optional.empty();
      do {
        requireActive(cancel);
        var page = raw.listActive(source, CollectionState.MAX_BATCH, cursor);
        for (var item : page.items())
          if (!observed.contains(item.externalId())) missing.put(item.externalId(), item);
        cursor = page.nextCursor();
      } while (cursor.isPresent());
    }

    private void recordItems() {
      var rows = state.putArray("items");
      for (RawItem item : missing.values()) {
        var row =
            rows.addObject()
                .put("id", item.externalId())
                .put("type", item.resourceType())
                .put("version", item.sourceVersion());
        item.sourceOrder()
            .ifPresent(
                order -> row.put("scheme", order.scheme()).put("position", order.position()));
        if (scope.directResourceTypes().contains(item.resourceType()))
          targets.put(
              item.externalId(), new AbsenceCandidate(item.externalId(), item.resourceType()));
      }
    }

    private void recordOwnership() {
      var ownership = state.putArray("ownership");
      for (var item : missing.values()) {
        if (scope.directResourceTypes().contains(item.resourceType())) continue;
        var owners =
            bodies
                .map(store -> store.owners(source, scope.fingerprint(), item.externalId()))
                .orElse(Set.of());
        if (owners.isEmpty()) unknownOwner = true;
        boolean onlyDeletedOwners =
            owners.stream()
                .allMatch(
                    owner -> raw.get(source, owner).orElse(null) instanceof RawRecord.Deleted);
        var relation = ownership.addObject().put("block", item.externalId()).putArray("owners");
        for (String owner : owners.stream().sorted().toList()) {
          relation.add(owner);
          var stored = Optional.ofNullable(ownerItems.get(owner)).or(() -> raw.get(source, owner));
          if (stored.isEmpty()
              || stored
                  .get()
                  .resourceType()
                  .filter(scope.directResourceTypes()::contains)
                  .isEmpty()) {
            unknownOwner = true;
            continue;
          }
          RawRecord value = stored.get();
          ownerItems.put(owner, value);
          if (value instanceof RawRecord.Deleted && !onlyDeletedOwners) continue;
          targets.put(owner, new AbsenceCandidate(owner, value.resourceType().orElseThrow()));
        }
      }
    }

    private void recordOwnerStates() {
      var evidence = state.putArray("ownerStates");
      for (var entry : ownerItems.entrySet()) {
        RawRecord value = entry.getValue();
        var owner =
            evidence
                .addObject()
                .put("id", entry.getKey())
                .put("active", value instanceof RawRecord.Active)
                .put("observed", observed.contains(entry.getKey()));
        value.lastSourceVersion().ifPresent(v -> owner.put("version", v));
        value
            .sourceOrder()
            .ifPresent(o -> owner.put("scheme", o.scheme()).put("position", o.position()));
        bodies
            .flatMap(store -> store.find(source, scope.fingerprint(), entry.getKey()))
            .ifPresent(
                body -> {
                  owner
                      .put("completedVersion", body.version())
                      .put("completedScheme", body.order().scheme())
                      .put("completedPosition", body.order().position());
                  body.completedRun().ifPresent(r -> owner.put("completedRun", r.value()));
                  owner.set(
                      "completedBlocks",
                      JSON.valueToTree(body.blocks().stream().sorted().toList()));
                });
        bodies
            .flatMap(store -> store.pending(source, scope.fingerprint(), entry.getKey()))
            .ifPresent(
                pending -> {
                  owner
                      .put("pendingVersion", pending.version())
                      .put("pendingRun", pending.run().value());
                  owner.set(
                      "pendingBlocks",
                      JSON.valueToTree(pending.observed().stream().sorted().toList()));
                });
      }
    }
  }

  private void requireActive(CancellationToken cancel) {
    if (cancel.isCancelled())
      throw new CallFailureException(
          "collection.state",
          CallFailure.of(CallFailure.Kind.CANCELLED, "Collection state read cancelled"));
  }
}
