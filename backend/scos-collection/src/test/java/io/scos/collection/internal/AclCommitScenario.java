package io.scos.collection.internal;

import io.scos.collection.internal.memory.MemoryBodyCompletionStore;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.IdentityCoverage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/** 본문·접근 커밋이 같은 포트와 정책을 쓰는 각본. */
public final class AclCommitScenario implements AutoCloseable {
  public static final SourceInstanceId SOURCE = new SourceInstanceId("acl-source");
  public static final RunId RUN = new RunId("acl-run");
  public static final String ID = "C1:1700000000.000001";
  public static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  public static final Instant T1 = T0.plusSeconds(1);
  public static final Instant T2 = T0.plusSeconds(2);
  public static final Duration OBSERVATION_STEP = Duration.ofSeconds(1);
  public static final Duration TTL = Duration.ofMinutes(1);
  public static final RawAcl.Observed GRANTED =
      RawAcl.observed(Status.OK, Set.of(AccessKey.parse("user:acl-source:U1")), T0);
  public final CommitContext.Ports ports;
  public final MutableClock clock = new MutableClock(T2);
  public final SourceDescriptor source = SourceInstances.noop(SOURCE, T0).descriptor();
  public final WorkClaim claim;
  public final BodyCompletionStore bodies;
  private final SourceAdapters adapters = SourceAdapters.of(List.of(new CarrierAdapter()));
  private CollectionStores stores;
  private int rawObservations;

  public AclCommitScenario(CommitContext.Ports ports, BodyCompletionStore bodies) {
    this.ports = ports;
    this.bodies = bodies;
    stores =
        ports
            .withReads(ports.runs(), ports.rawItems(), new ObservedRawAcls())
            .stores()
            .withBodies(bodies);
    ports.sources().save(SourceInstances.noop(SOURCE, T0));
    claim = ports.claims().tryClaim(SOURCE, new OwnerId("worker"), TTL, T2).orElseThrow();
    ports.runs().start(RUN, SOURCE, T2);
    ports.rawItems().apply(SOURCE, item(GRANTED).upsert());
    ports.rawAcls().observe(SOURCE, ID, GRANTED, T0);
    ports.outbox().requestProjection(SOURCE, T0);
  }

  public static AclCommitScenario memory() {
    return new AclCommitScenario(
        CommitContext.Memory.create().ports(), new MemoryBodyCompletionStore());
  }

  public RawItems item(RawAcl acl) {
    return RawItems.item(ID).version("v1").observedAt(T0).acl(acl);
  }

  public AclCommit accesses(boolean enabled) {
    return new AclCommit(stores, adapters, clock, enabled);
  }

  public void access(RawAcl acl) {
    accesses(true).commit(claim, RUN, source, new AclPage(Map.of(ID, acl), List.of(), List.of()));
  }

  public void givenExpiredRaw() {
    expireBefore(T0);
    if (acl().reason() != AccessFailureReason.EXPIRED)
      throw new IllegalStateException("given 준비 실패: 원본 ACL이 만료되어야 한다");
    resetRawObservations();
  }

  public void resetRawObservations() {
    rawObservations = 0;
  }

  public int rawObservations() {
    return rawObservations;
  }

  private final class ObservedRawAcls implements RawAclStore {
    @Override
    public boolean observe(SourceInstanceId source, String id, RawAcl acl, Instant fallback) {
      rawObservations++;
      return ports.rawAcls().observe(source, id, acl, fallback);
    }

    @Override
    public int documentsMissingAcl(SpaceId space, SourceInstanceId source) {
      return ports.rawAcls().documentsMissingAcl(space, source);
    }

    @Override
    public Map<String, RawAcl.Observed> get(SourceInstanceId source, Collection<String> ids) {
      return ports.rawAcls().get(source, ids);
    }

    @Override
    public void delete(SourceInstanceId source, Collection<String> ids) {
      ports.rawAcls().delete(source, ids);
    }
  }

  public void expireBefore(Instant cutoff) {
    accesses(true).expireBefore(claim, RUN, source, List.of(ID), cutoff);
  }

  public void beginCommitNotifications() {
    TransactionSynchronizationManager.initSynchronization();
  }

  public void commitNotifications() {
    TransactionSynchronizationUtils.triggerAfterCommit();
  }

  @Override
  public void close() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
  }

  public PageCommit pages(boolean enabled) {
    return new PageCommit(
        stores, clock, TestMetrics.collection(), new Progress(clock), adapters, enabled);
  }

  public PageCommitResult collect(RawItems item, boolean enabled) {
    return pages(enabled)
        .commit(
            claim,
            RUN,
            0,
            CollectionPage.ofItems(List.of(item.build()), new Checkpoint(1, "{}")),
            source);
  }

  public void collectForInstance(SourceInstanceId instance) {
    var configured = SourceInstances.noop(instance, T0);
    ports.sources().save(configured);
    var owned = ports.claims().tryClaim(instance, new OwnerId("worker"), TTL, T2).orElseThrow();
    var run = new RunId("non-canonical-run");
    ports.runs().start(run, instance, T2);
    pages(true)
        .commit(
            owned,
            run,
            0,
            CollectionPage.ofItems(List.of(item(GRANTED).build()), new Checkpoint(1, "{}")),
            configured.descriptor());
  }

  public void unchangedBodyForInstance(SourceInstanceId instance) {
    collectForInstance(instance);
    pages(true)
        .commit(
            ports.claims().current(instance).orElseThrow(),
            new RunId("non-canonical-run"),
            1,
            CollectionPage.ofItems(List.of(item(GRANTED).build()), new Checkpoint(1, "{}")),
            SourceInstances.noop(instance, T0).descriptor());
  }

  public void collectNonCarrierCollision() {
    collect(
        item(RawAcl.observed(Status.OK, Set.of(AccessKey.parse("user:acl-source:U2")), T1))
            .resourceType("slack.fragment"),
        true);
  }

  public RawAcl.Observed acl() {
    return ports.rawAcls().get(SOURCE, List.of(ID)).get(ID);
  }

  public long requestVersion() {
    return ports.outbox().request(SOURCE).orElseThrow().version();
  }

  public AccessMerge mergeAccess() {
    return ports
        .accessObservations()
        .merge(
            RUN, SOURCE, AccessMerge.COMPLETE, IdentityCoverage.PARTIAL, clock.instant(), () -> {});
  }

  public void failOutbox() {
    stores = stores.withOutbox(new FailingOutbox());
  }

  public void explicitDelete(Optional<SourceOrder> order) {
    var deletion = new RawMutation.Delete(ID, Optional.empty(), order, Optional.empty(), T2);
    pages(true)
        .commit(
            claim,
            RUN,
            0,
            new CollectionPage(List.of(deletion), List.of(), new Checkpoint(1, "{}")),
            source);
  }

  public void snapshotAbsence() {
    ports.rawItems().apply(SOURCE, RawItems.item("retained-message").upsert());
    ports.runs().observe(RUN, Set.of("retained-message"));
    var completion = new CommitContext(ports, clock).completion();
    completion.recordSnapshotBaseline(SOURCE, RUN);
    completion.complete(
        new RunCompletion.Input(
            claim,
            RUN,
            RunOutcome.succeeded(0),
            CollectionCoverage.COMPLETE_SNAPSHOT,
            T2,
            AccessResult.SKIPPED,
            Optional.empty(),
            Optional.of(source)));
  }

  public void bodyAbsence() {
    var order = new SourceOrder("body.v1", "0001");
    ports.rawItems().apply(SOURCE, RawItems.item("owner").version("v1").order(order).upsert());
    bodies.save(
        SOURCE, new BodyCompletionStore.Body("roots", "owner", "v1", order, T0, Set.of(ID)));
    var observation =
        new BodyObservation(
            "roots", "owner", "v1", order, Set.of(), BodyObservation.Outcome.COMPLETE);
    pages(true)
        .commit(
            claim,
            RUN,
            0,
            new CollectionPage(List.of(), List.of(), new Checkpoint(1, "{}"), List.of(observation)),
            source);
  }

  public void domainSharedFileAndVerifiedIdentity() {
    ports.rawItems().apply(SOURCE, RawItems.item("F1").upsert());
    ports
        .rawAcls()
        .observe(
            SOURCE,
            "F1",
            RawAcl.observed(
                Status.OK, Set.of(AccessKey.parse("domain:acl-source:example.com")), T0),
            T0);
    accesses(true)
        .commit(
            claim,
            RUN,
            source,
            new AclPage(
                Map.of(),
                List.of(),
                List.of(
                    new SourceIdentityRecord(
                        "U7", Optional.of("u7@example.com"), true, false, false, T1))));
  }

  public void conflictingHiddenIdentityRestrictsFile() {
    accesses(true)
        .commit(
            claim,
            RUN,
            source,
            new AclPage(
                Map.of("F1", RawAcl.observed(Status.OK, Set.of(), T1)),
                List.of(),
                List.of(
                    new SourceIdentityRecord("U7", Optional.empty(), false, false, false, T1))));
  }

  private static final class CarrierAdapter implements SourceAdapter {
    @Override
    public String kind() {
      return SourceInstances.NOOP_KIND;
    }

    @Override
    public void validateConfig(SourceConfig config) {}

    @Override
    public CollectionCoverage coverage(
        SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
      return CollectionCoverage.INCREMENTAL;
    }

    @Override
    public void collect(CollectionInput input, PageSink sink) {}

    @Override
    public AccessPolicy accessPolicy() {
      return AccessPolicy.supported(Set.of("slack.message"), Set.of());
    }
  }

  private static final class FailingOutbox extends MemoryOutboxPort {
    @Override
    public synchronized void requestProjection(SourceInstanceId source, Instant at) {
      throw new IllegalStateException("injected ACL outbox failure");
    }
  }
}
