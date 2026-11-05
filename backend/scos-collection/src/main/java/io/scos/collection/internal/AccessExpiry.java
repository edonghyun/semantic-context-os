package io.scos.collection.internal;

import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.engine.EventLog;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItem;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceOwnershipException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 비활성 소스도 관측 상한으로 제한한다. 외부 호출·수집 점유·투영 성공과 독립된 제한 전용 유지보수다. */
public final class AccessExpiry {
  private static final Logger log = LoggerFactory.getLogger(AccessExpiry.class);
  private static final int PAGE_SIZE = 500;
  private static final Duration MAX_SWEEP_INTERVAL = Duration.ofMinutes(1);
  private final CollectionStores stores;
  private final SourceAdapters adapters;
  private final ProjectionSpaces spaces;
  private final AclStore acls;
  private final DocumentOwnership ownership;
  private final SourceAccessDirectory directory;
  private final Clock clock;
  private final AccessCollection.Options options;
  private final boolean enabled;
  private @Nullable Instant lastSweep;

  private record ExpiredPage(Optional<String> nextCursor, int rawClosed, int documentsClosed) {}

  private record DirectoryClosures(int groups, int identities) {}

  public AccessExpiry(
      CollectionStores stores,
      SourceAdapters adapters,
      ProjectionSpaces spaces,
      AclStore acls,
      DocumentOwnership ownership,
      SourceAccessDirectory directory,
      Clock clock,
      AccessCollection.Options options,
      boolean enabled) {
    this.stores = stores;
    this.adapters = adapters;
    this.spaces = spaces;
    this.acls = acls;
    this.ownership = ownership;
    this.directory = directory;
    this.clock = clock;
    this.options = options;
    this.enabled = enabled;
  }

  /** 데몬 틱이 호출한다. 최대 1분 간격이며 취소는 배치 사이와 쓰기 직전에 확인한다. 실패한 소스는 다음 틱에 재시도한다. */
  public void sweep(CancellationToken cancel) {
    if (!enabled || cancel.isCancelled()) return;
    Instant now = clock.instant();
    Duration interval =
        options.maxAge().compareTo(MAX_SWEEP_INTERVAL) < 0 ? options.maxAge() : MAX_SWEEP_INTERVAL;
    if (lastSweep != null && lastSweep.plus(interval).isAfter(now)) return;
    boolean complete = true;
    for (SourceInstance source : stores.sources().orElseThrow().listAll()) {
      if (cancel.isCancelled()) return;
      try {
        expireSource(source, now.minus(options.maxAge()), cancel);
      } catch (RuntimeException failure) {
        if (cancel.isCancelled()) return;
        complete = false;
        EventLog.warn(log, "collection.access.expiry_failed")
            .source(source.id())
            .cause(failure)
            .log();
      }
    }
    if (complete) lastSweep = now;
  }

  private void expireSource(SourceInstance source, Instant cutoff, CancellationToken cancel) {
    expireDirectoryAndReport(source, cutoff, cancel);
    var adapter = adapters.forKind(source.kind());
    if (adapter.isEmpty()) return;
    AccessPolicy policy = adapter.orElseThrow().accessPolicy();
    if (policy.refreshKinds().isEmpty()) return;
    Optional<String> cursor = Optional.empty();
    do {
      if (cancel.isCancelled()) return;
      var expired = expireRawPage(source, policy, cursor, cancel);
      reportExpired(source, expired, cutoff);
      cursor = expired.nextCursor();
    } while (cursor.isPresent());
  }

  private void expireDirectoryAndReport(
      SourceInstance source, Instant cutoff, CancellationToken cancel) {
    // 선조회 트랜잭션의 디렉터리 잠금을 먼저 놓고, 쓰기는 소스→디렉터리 순서로 다시 잠근다.
    var pending =
        Objects.requireNonNull(
            stores
                .tx()
                .execute(
                    status -> {
                      checkSource(source, cancel);
                      return expiredDirectory(source, cutoff);
                    }));
    if (pending.groups().isEmpty() && pending.identities().isEmpty()) return;
    var closed =
        Objects.requireNonNull(
            stores
                .tx()
                .execute(
                    status -> {
                      requireSource(source, cancel);
                      return expireDirectory(source, cutoff, cancel);
                    }));
    if (closed.groups() + closed.identities() > 0)
      EventLog.warn(log, "collection.access.directory_expired")
          .source(source.id())
          .detail(
              "groupsClosed",
              closed.groups(),
              "identitiesClosed",
              closed.identities(),
              "cutoff",
              cutoff.toString(),
              "reason",
              "expired")
          .log();
  }

  private ExpiredPage expireRawPage(
      SourceInstance source,
      AccessPolicy policy,
      Optional<String> cursor,
      CancellationToken cancel) {
    return Objects.requireNonNull(
        stores
            .tx()
            .execute(
                status -> {
                  requireNotCancelled(cancel);
                  Instant cutoff = clock.instant().minus(options.maxAge());
                  var selected = expiredCandidates(source, policy, cursor, cutoff);
                  if (!selected.items().isEmpty()) {
                    // 잠금을 기다리는 동안 새 관측이 올 수 있으므로 최신 후보로 다시 판정한다.
                    requireSource(source, cancel);
                    cutoff = clock.instant().minus(options.maxAge());
                    selected = expiredCandidates(source, policy, cursor, cutoff);
                  }
                  return expirePage(source, policy, selected, cutoff, cancel);
                }));
  }

  private RawItemStore.Page expiredCandidates(
      SourceInstance source, AccessPolicy policy, Optional<String> cursor, Instant cutoff) {
    return stores
        .rawItems()
        .accessCandidates(
            source.id(), policy.refreshKinds(), Optional.of(cutoff), PAGE_SIZE, cursor);
  }

  private void reportExpired(SourceInstance source, ExpiredPage expired, Instant cutoff) {
    if (expired.rawClosed() + expired.documentsClosed() > 0)
      EventLog.warn(log, "collection.access.expired")
          .source(source.id())
          .detail(
              "rawClosed",
              expired.rawClosed(),
              "documentsClosed",
              expired.documentsClosed(),
              "cutoff",
              cutoff.toString(),
              "reason",
              "expired")
          .log();
  }

  private SourceAccessDirectory.Snapshot expiredDirectory(SourceInstance source, Instant cutoff) {
    var snapshot = directory.current(source.id());
    var groups = new HashMap<String, GroupSnapshot>();
    for (var group : snapshot.groups().values())
      if (!group.members().isEmpty() && !group.observedAt().isAfter(cutoff))
        groups.put(group.groupId(), group.withObservation(Set.of(), group.observedAt()));
    var identities = new HashMap<String, SourceIdentityRecord>();
    for (var identity : snapshot.identities().values())
      if (!identity.deleted() && !identity.observedAt().isAfter(cutoff))
        identities.put(
            identity.sourceUserId(),
            new SourceIdentityRecord(
                identity.sourceUserId(),
                identity.email(),
                identity.emailVerified(),
                identity.guest(),
                true,
                identity.observedAt()));
    return new SourceAccessDirectory.Snapshot(identities, groups);
  }

  private DirectoryClosures expireDirectory(
      SourceInstance source, Instant cutoff, CancellationToken cancel) {
    var expired = expiredDirectory(source, cutoff);
    requireNotCancelled(cancel);
    if (!expired.groups().isEmpty() || !expired.identities().isEmpty())
      directory.save(source.id(), expired);
    return new DirectoryClosures(expired.groups().size(), expired.identities().size());
  }

  private ExpiredPage expirePage(
      SourceInstance source,
      AccessPolicy policy,
      RawItemStore.Page page,
      Instant cutoff,
      CancellationToken cancel) {
    requireNotCancelled(cancel);
    int rawClosed = 0;
    int documentsClosed = 0;
    for (RawItem item : page.items()) {
      if (cancel.isCancelled()) throw new IllegalStateException("Access expiry cancelled");
      if (!policy.refreshes(item)) continue;
      RawAcl acl = item.acl();
      if (!(acl instanceof RawAcl.Observed observed) || observed.observedAt().isAfter(cutoff))
        continue;
      requireSource(source, cancel);
      boolean changed =
          !expired(observed)
              && stores
                  .rawAcls()
                  .observe(
                      source.id(),
                      item.externalId(),
                      RawAcl.failed(AccessFailureReason.EXPIRED, observed.observedAt()),
                      clock.instant());
      if (changed) rawClosed++;
      var document = policy.documentOf(item);
      SpaceId space =
          document
              .flatMap(id -> ownership.ownedSpace(source.id().value(), id))
              .orElseGet(() -> spaces.resolve(source));
      if (document.isPresent() && closeDocument(source, space, document.orElseThrow()))
        documentsClosed++;
      stores
          .rawItems()
          .completeAccessExpiry(
              source.id(), item.externalId(), observed.observedAt(), space, document);
    }
    if (rawClosed > 0) stores.outbox().requestProjection(source.id(), clock.instant());
    return new ExpiredPage(page.nextCursor(), rawClosed, documentsClosed);
  }

  private static boolean expired(RawAcl.Observed acl) {
    return acl.status() == Status.FAILED && acl.reason() == AccessFailureReason.EXPIRED;
  }

  private boolean closeDocument(SourceInstance source, SpaceId space, String document) {
    DocumentAcl candidate = acls.get(space, List.of(document)).get(document);
    if (candidate == null || candidate.status() == Status.FAILED) return false;
    try {
      if (ownership.claim(space, source.id().value(), document)
          == DocumentOwnership.Claim.OTHER_SOURCE) return false;
      DocumentAcl previous = acls.get(space, List.of(document)).get(document);
      if (previous == null || previous.status() == Status.FAILED) return false;
      Instant next = previous.observedAt().plus(1, ChronoUnit.MICROS);
      Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
      acls.put(
          space,
          List.of(
              new DocumentAcl(
                  document,
                  Status.FAILED,
                  Set.of(),
                  now.isBefore(next) ? next : now,
                  AccessFailureReason.EXPIRED)));
      return true;
    } catch (SpaceOwnershipException foreignSpace) {
      // 소유 공간이 다른 문서는 그 소스의 만료 처리가 제한한다.
      return false;
    }
  }

  private void requireSource(SourceInstance source, CancellationToken cancel) {
    requireNotCancelled(cancel);
    stores.sources().orElseThrow().requireCollectionConfiguration(source.descriptor());
  }

  /** 읽기 전에 캡처한 설정을 확인하되, 변경 없는 소스의 쓰기 잠금을 만들지 않는다. */
  private void checkSource(SourceInstance source, CancellationToken cancel) {
    requireNotCancelled(cancel);
    stores
        .sources()
        .orElseThrow()
        .get(source.id())
        .orElseThrow(SourceConfigurationChangedException::new)
        .requireCollectionConfiguration(source.descriptor());
  }

  private void requireNotCancelled(CancellationToken cancel) {
    if (cancel.isCancelled()) throw new IllegalStateException("Access expiry cancelled");
  }
}
