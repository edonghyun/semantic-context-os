package io.scos.collection.internal;

import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.engine.EventLog;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AccessModel;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceDescriptor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 같은 수집 점유 안에서 본문과 독립적으로 접근 대상을 보충한다. 병합과 결과 저장은 마감 단계가 맡는다. */
public final class AccessCollection {
  /** 대상 관측 결과와 디렉터리 자체의 전체성은 독립이다. */
  public record Result(AccessResult result, AccessMerge directory) {}

  private static final Logger log = LoggerFactory.getLogger(AccessCollection.class);
  // 원본 스캔과 API 없는 보충의 저장 배치를 제한한다. 공급자 API 페이지 크기와는 무관하다.
  private static final int PAGE_SIZE = 500;

  /** 재관측 실패가 계속되어도 다음 접근 시도에서 이전 권한을 닫는 최대 나이. */
  public record Options(Duration maxAge) {
    // 최대 6시간 재관측 주기의 네 배: 일시 오류는 허용하되 하루 넘은 권한은 유지하지 않는다.
    public static final Options DEFAULTS = new Options(Duration.ofHours(24));

    public Options {
      if (maxAge.isNegative() || maxAge.isZero())
        throw new IllegalArgumentException("maxAge must be positive");
    }
  }

  private final ProjectionSpaces spaces;
  private final Options options;
  private final CollectionStores stores;
  private final AclCommit acls;
  private final CallExecutor calls;
  private final DomainPolicy domains;
  private final Clock clock;
  private final boolean collectAccess;
  private final AccessKeyInstances instances = new AccessKeyInstances();

  public AccessCollection(
      CollectionStores stores,
      SourceAdapters adapters,
      CallExecutor calls,
      DomainPolicy domains,
      Clock clock,
      boolean collectAccess,
      ProjectionSpaces spaces,
      Options options) {
    this.stores = stores;
    this.spaces = spaces;
    this.options = options;
    this.acls = new AclCommit(stores, adapters, clock, collectAccess, options);
    this.calls = calls;
    this.domains = domains;
    this.clock = clock;
    this.collectAccess = collectAccess;
  }

  /**
   * 미지원은 원본을 읽거나 어댑터를 호출하지 않는다. 대상이 없어도 지원 원천의 전체 그룹·신원을 요청한다. 취소·공급자/저장 오류는 INCOMPLETE, 점유 상실은
   * CLAIM_LOST로 격리하며 이미 커밋한 페이지는 보존한다. COMPLETE는 병합 전 수집 결과다.
   */
  public Result collect(
      SourceDescriptor source,
      SourceAdapter adapter,
      WorkClaim claim,
      RunId run,
      LeaseHeartbeat heartbeat,
      CancellationToken cancel) {
    if (!collectAccess) return new Result(AccessResult.SKIPPED, AccessMerge.INCOMPLETE);
    if (cancel.isCancelled() || !instances.admits(source.id()))
      return new Result(AccessResult.INCOMPLETE, AccessMerge.INCOMPLETE);
    if (adapter.accessPolicy().model() == AccessModel.UNSUPPORTED
        && !adapter.accessPolicy().permitsScope(source))
      return new Result(AccessResult.UNSUPPORTED, AccessMerge.INCOMPLETE);
    return new Attempt(source, adapter, claim, run, heartbeat, cancel).collect();
  }

  /** 한 접근 시도의 점유·취소·대상 선정과 페이지 저장을 함께 소유한다. */
  private final class Attempt {
    private final SourceDescriptor source;
    private final SourceAdapter adapter;
    private final WorkClaim claim;
    private final RunId run;
    private final LeaseHeartbeat heartbeat;
    private final CancellationToken cancel;
    private final Set<String> pending = new LinkedHashSet<>();
    private boolean directoryComplete;

    Attempt(
        SourceDescriptor source,
        SourceAdapter adapter,
        WorkClaim claim,
        RunId run,
        LeaseHeartbeat heartbeat,
        CancellationToken cancel) {
      this.source = source;
      this.adapter = adapter;
      this.claim = claim;
      this.run = run;
      this.heartbeat = heartbeat;
      this.cancel = cancel;
    }

    Result collect() {
      try {
        requireActive();
        if (adapter.accessPolicy().model() == AccessModel.UNSUPPORTED) {
          if (adapter.accessPolicy().permitsScope(source)) requestMissingAclBackfill();
          return new Result(AccessResult.UNSUPPORTED, AccessMerge.INCOMPLETE);
        }
        boolean allTargetsObserved = collectTargets();
        return new Result(
            allTargetsObserved ? AccessResult.COMPLETE : AccessResult.INCOMPLETE,
            AccessMerge.COMPLETE);
      } catch (ClaimLostException lost) {
        return new Result(AccessResult.CLAIM_LOST, AccessMerge.INCOMPLETE);
      } catch (RuntimeException incomplete) {
        try {
          expireUnrefreshed();
        } catch (ClaimLostException lost) {
          return new Result(AccessResult.CLAIM_LOST, AccessMerge.INCOMPLETE);
        } catch (RuntimeException expirationFailure) {
          if (!expirationFailure.equals(incomplete)) incomplete.addSuppressed(expirationFailure);
        }
        EventLog.warn(log, "collection.access.incomplete")
            .source(source.id())
            .run(run.value())
            .cause(incomplete)
            .log();
        return new Result(
            AccessResult.INCOMPLETE,
            directoryComplete ? AccessMerge.COMPLETE : AccessMerge.INCOMPLETE);
      }
    }

    private void requestMissingAclBackfill() {
      stores
          .tx()
          .executeWithoutResult(
              status -> {
                Instant now = clock.instant();
                requireActive();
                new WriteFence(stores).requireWritable(claim, run, Optional.of(source), now);
                var instance = stores.sources().orElseThrow().get(source.id()).orElseThrow();
                int missing =
                    stores.rawAcls().documentsMissingAcl(spaces.resolve(instance), source.id());
                requireActive();
                if (missing > 0) stores.outbox().requestProjection(source.id(), now);
              });
    }

    private boolean collectTargets() {
      requireActive();
      var input = new AccessInput(source, targets(), calls, cancel, domains, this::storedContent);
      pending.addAll(input.externalIds());
      requireActive();
      adapter.accessPolicy().collect(input, this::commit);
      // 정상 디렉터리 반환 뒤의 대상 닫기 실패가 이미 관측한 전체성을 지우지 않는다.
      directoryComplete = true;
      expireUnrefreshed();
      requireActive();
      if (!pending.isEmpty()) {
        failMissingTargets();
        return false;
      }
      return true;
    }

    private Optional<String> storedContent(String externalId) {
      requireActive();
      return stores
          .rawItems()
          .get(source.id(), externalId)
          .filter(RawRecord.Active.class::isInstance)
          .map(RawRecord.Active.class::cast)
          .flatMap(active -> active.item().content())
          .flatMap(
              content ->
                  switch (content) {
                    case RawContent.Inline inline -> Optional.of(inline.valueJson());
                    case RawContent.Blob blob ->
                        stores
                            .rawItems()
                            .blob(blob.sha256())
                            .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
                  });
    }

    private void expireUnrefreshed() {
      if (cancel.isCancelled() || adapter.accessPolicy().refreshKinds().isEmpty()) return;
      Optional<String> cursor = Optional.empty();
      do {
        requireActive();
        Instant cutoff = clock.instant().minus(options.maxAge());
        var page =
            stores
                .rawItems()
                .accessCandidates(
                    source.id(),
                    adapter.accessPolicy().refreshKinds(),
                    Optional.of(cutoff),
                    PAGE_SIZE,
                    cursor);
        var carriers = page.items().stream().map(RawItem::externalId).toList();
        if (!carriers.isEmpty()) acls.expireBefore(claim, run, source, carriers, cutoff);
        cursor = page.nextCursor();
      } while (cursor.isPresent());
    }

    private void failMissingTargets() {
      var missing = List.copyOf(pending);
      for (int offset = 0; offset < missing.size(); offset += PAGE_SIZE) {
        requireActive();
        acls.failMissing(
            claim,
            run,
            source,
            missing.subList(offset, Math.min(offset + PAGE_SIZE, missing.size())));
      }
    }

    private List<String> targets() {
      Set<String> observed = stores.runs().observations(run);
      List<RawItem> selected = new ArrayList<>();
      Optional<String> cursor = Optional.empty();
      do {
        requireActive();
        var page =
            stores
                .rawItems()
                .accessCandidates(
                    source.id(),
                    adapter.accessPolicy().carrierKinds(),
                    Optional.empty(),
                    PAGE_SIZE,
                    cursor);
        page.items().stream()
            .filter(item -> adapter.accessPolicy().carries(item))
            .filter(item -> needsAcl(item, observed))
            .forEach(selected::add);
        cursor = page.nextCursor();
      } while (cursor.isPresent());
      selected.sort(Comparator.comparing(this::lastObservedAt).thenComparing(RawItem::externalId));
      return resolveTargets(selected);
    }

    private Instant lastObservedAt(RawItem item) {
      return item.acl() instanceof RawAcl.Observed acl ? acl.observedAt() : Instant.MIN;
    }

    private List<String> resolveTargets(List<RawItem> selected) {
      List<String> remote = new ArrayList<>();
      for (int offset = 0; offset < selected.size(); offset += PAGE_SIZE) {
        requireActive();
        Map<String, RawAcl> local = new LinkedHashMap<>();
        for (RawItem item :
            selected.subList(offset, Math.min(offset + PAGE_SIZE, selected.size()))) {
          var acl = adapter.accessPolicy().localAcl(source, item.externalId(), clock.instant());
          if (acl.orElse(null) instanceof RawAcl.Observed observation)
            local.put(item.externalId(), observation);
          else remote.add(item.externalId());
        }
        if (!local.isEmpty()) commit(new AclPage(local, List.of(), List.of()));
      }
      return remote;
    }

    private boolean needsAcl(RawItem item, Set<String> observed) {
      if (adapter.accessPolicy().refreshes(item) || observed.contains(item.externalId()))
        return true;
      return !(item.acl() instanceof RawAcl.Observed acl)
          || acl.status() == Status.UNKNOWN
          || acl.status() == Status.FAILED;
    }

    private void commit(AclPage page) {
      requireActive();
      acls.commit(claim, run, source, page);
      page.acls()
          .forEach(
              (id, acl) -> {
                if (acl instanceof RawAcl.Observed) pending.remove(id);
              });
    }

    private void requireActive() {
      if (cancel.isCancelled())
        throw new CallFailureException(
            "access.collect", CallFailure.of(CallFailure.Kind.CANCELLED, "Access cancelled"));
      heartbeat.assertHealthy();
      stores
          .tx()
          .executeWithoutResult(status -> stores.claims().requireActive(claim, clock.instant()));
    }
  }
}
