package io.scos.collection.internal;

import io.scos.engine.EventLog;
import io.scos.engine.support.text.Text;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceIdentityRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 본문·체크포인트를 건드리지 않고 carrier ACL과 투영 요청을 같은 트랜잭션에 저장한다. */
public final class AclCommit {
  private static final Logger log = LoggerFactory.getLogger(AclCommit.class);
  private final CollectionStores stores;
  private final WriteFence fence;
  private final SourceAdapters adapters;
  private final Clock clock;
  private final boolean collectAccess;
  private final AccessCollection.Options options;
  private final RawAclValidation validation = new RawAclValidation();
  private final AccessKeyInstances instances = new AccessKeyInstances();

  public AclCommit(
      CollectionStores stores, SourceAdapters adapters, Clock clock, boolean collectAccess) {
    this(stores, adapters, clock, collectAccess, AccessCollection.Options.DEFAULTS);
  }

  public AclCommit(
      CollectionStores stores,
      SourceAdapters adapters,
      Clock clock,
      boolean collectAccess,
      AccessCollection.Options options) {
    this.options = options;
    this.stores = stores;
    this.fence = new WriteFence(stores);
    this.adapters = adapters;
    this.clock = clock;
    this.collectAccess = collectAccess;
  }

  /** 점유·running run·캡처한 설정이 유효해야 한다. 실패하면 ACL과 요청 모두 롤백한다. */
  public void commit(WorkClaim claim, RunId run, SourceDescriptor expectedSource, AclPage page) {
    commitPage(claim, run, expectedSource, capTimes(expectedSource, page, clock.instant()));
  }

  private void commitPage(
      WorkClaim claim, RunId run, SourceDescriptor expectedSource, AclPage page) {
    stores
        .tx()
        .executeWithoutResult(
            status -> {
              Instant now = clock.instant();
              fence.requireWritable(claim, run, Optional.of(expectedSource), now);
              boolean changed = false;
              for (var entry : page.acls().entrySet())
                changed |= observe(expectedSource, entry.getKey(), entry.getValue(), now);
              if (collectAccess) {
                stores.accessObservations().stage(run, page);
                stores.runs().observeUnreadableAcls(run, page.aclUnreadable());
              }
              if (changed) stores.outbox().requestProjection(claim.source(), now);
            });
  }

  /** 공급자 시계가 저장 고수위를 미래로 밀지 못하게 모든 수집 관측을 로컬 수신 시각에 맞춘다. */
  private AclPage capTimes(SourceDescriptor source, AclPage page, Instant now) {
    Map<String, RawAcl> acls = new LinkedHashMap<>();
    page.acls().forEach((id, acl) -> acls.put(id, capTime(source, acl, now)));
    var groups =
        page.groups().stream()
            .map(
                group ->
                    group.withObservation(
                        group.members(), capTime(source, "group", group.observedAt(), now)))
            .toList();
    var identities =
        page.identities().stream()
            .map(
                identity ->
                    new SourceIdentityRecord(
                        identity.sourceUserId(),
                        identity.email(),
                        identity.emailVerified(),
                        identity.guest(),
                        identity.deleted(),
                        capTime(source, "identity", identity.observedAt(), now)))
            .toList();
    return new AclPage(acls, groups, identities, page.aclUnreadable());
  }

  private RawAcl capTime(SourceDescriptor source, RawAcl acl, Instant now) {
    if (!(acl instanceof RawAcl.Observed observed)) return acl;
    return new RawAcl.Observed(
        observed.status(),
        observed.keys(),
        capTime(source, "acl", observed.observedAt(), now),
        observed.reason());
  }

  private Instant capTime(SourceDescriptor source, String kind, Instant observedAt, Instant now) {
    if (!observedAt.isAfter(now)) return observedAt;
    EventLog.warn(log, "collection.access.future_observation")
        .source(source.id())
        .detail("kind", kind, "observedAt", observedAt.toString(), "cappedAt", now.toString())
        .log();
    return now;
  }

  /** 이번 시도의 관측 누락은 과거 공급자 응답이 아니다. 저장 고수위를 보존하며 같은 시각에서도 FAILED로 좁힌다. */
  public void failMissing(
      WorkClaim claim, RunId run, SourceDescriptor source, List<String> externalIds) {
    stores
        .tx()
        .executeWithoutResult(
            status -> {
              Instant now = clock.instant();
              var previous = stores.rawAcls().get(source.id(), externalIds);
              Map<String, RawAcl> failed = new LinkedHashMap<>();
              for (String id : externalIds) {
                var old = previous.get(id);
                Instant at = old != null && old.observedAt().isAfter(now) ? old.observedAt() : now;
                failed.put(id, RawAcl.failed(AccessFailureReason.MISSING_TARGET, at));
              }
              commitPage(claim, run, source, new AclPage(failed, List.of(), List.of()));
            });
  }

  /** 만료는 새 원천 관측이 아니다. 잠긴 현재 값의 시각을 보존하며 FAILED로만 좁힌다. */
  public void expireBefore(
      WorkClaim claim,
      RunId run,
      SourceDescriptor source,
      List<String> externalIds,
      Instant cutoff) {
    stores
        .tx()
        .executeWithoutResult(
            status -> {
              Instant now = clock.instant();
              fence.requireWritable(claim, run, Optional.of(source), now);
              boolean changed = false;
              for (var entry : stores.rawAcls().get(source.id(), externalIds).entrySet()) {
                var old = entry.getValue();
                if (!old.observedAt().isAfter(cutoff)
                    && !(old.status() == Status.FAILED
                        && old.reason() == AccessFailureReason.EXPIRED)) {
                  boolean restricted =
                      stores
                          .rawAcls()
                          .observe(
                              source.id(),
                              entry.getKey(),
                              RawAcl.failed(AccessFailureReason.EXPIRED, old.observedAt()),
                              now);
                  changed |= restricted;
                  if (restricted)
                    reportClosure(source, entry.getKey(), AccessFailureReason.EXPIRED);
                }
              }
              if (changed) stores.outbox().requestProjection(source.id(), now);
            });
  }

  /** 본문이 거부돼도 입력 자체가 carrier인 관측만 저장된 carrier에 적용한다. */
  boolean observe(SourceDescriptor source, RawItem item, Instant fallbackAt) {
    if (!collectAccess || !adapters.require(source.kind()).accessPolicy().carries(item))
      return false;
    return observe(source, item.externalId(), capTime(source, item.acl(), fallbackAt), fallbackAt);
  }

  private boolean observe(
      SourceDescriptor source, String externalId, RawAcl acl, Instant fallbackAt) {
    if (!collectAccess) return false;
    if (!instances.admits(source.id())) {
      EventLog.warn(log, "collection.acl.skipped")
          .source(source.id())
          .detail("reason", "non_canonical_instance")
          .log();
      return false;
    }
    var adapter = adapters.require(source.kind());
    if (!(stores.rawItems().get(source.id(), externalId).orElse(null)
            instanceof RawRecord.Active active)
        || !adapter.accessPolicy().carries(active.item())) return false;
    var valid = validation.validate(source, adapter.accessPolicy(), acl);
    var previous = stores.rawAcls().get(source.id(), List.of(externalId)).get(externalId);
    boolean changed = stores.rawAcls().observe(source.id(), externalId, valid, fallbackAt);
    if (changed) {
      var saved = stores.rawAcls().get(source.id(), List.of(externalId)).get(externalId);
      if (saved != null && saved.status() == Status.FAILED)
        reportClosure(source, externalId, saved.reason());
    }
    boolean refreshed =
        adapter.accessPolicy().refreshes(active.item())
            && previous != null
            && previous.status() == Status.OK
            && !previous.observedAt().isAfter(fallbackAt.minus(options.maxAge()))
            && valid instanceof RawAcl.Observed observed
            && observed.status() == Status.OK
            && observed.observedAt().isAfter(fallbackAt.minus(options.maxAge()));
    return changed || refreshed;
  }

  private void reportClosure(SourceDescriptor source, String id, AccessFailureReason reason) {
    Runnable report =
        () ->
            EventLog.warn(log, "collection.acl.closed")
                .source(source.id())
                .detail("reason", reason.wire(), "rawRef", Text.sha256Hex(id))
                .log();
    if (!TransactionSynchronizationManager.isSynchronizationActive()) report.run();
    else
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              report.run();
            }
          });
  }
}
