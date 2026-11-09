package io.scos.server;

import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SnapshotShrinkOverrides;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.WorkClaim;
import io.scos.engine.Freshness;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.ListingField;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 조회 결과 → 조회 CLI JSON. 키 순서는 기존 클라이언트가 읽는 출력 형식 그대로다(바꾸면 안 된다). 없는 선택 값은 키째 뺀다. 시각은 toISOString
 * 형식(UTC 밀리초). 어댑터 소유 JSON(metadata·content·locator)은 저장된 원문을 읽어 키 순서를 그대로 둔다.
 */
final class SourceQueryJson {
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final ObjectMapper json;

  SourceQueryJson(ObjectMapper json) {
    this.json = json;
  }

  /** 어댑터가 없으면 incremental·none. coverage는 모드·체크포인트 없이 묻는다. */
  ArrayNode sources(List<SourceInstance> instances, SourceAdapters adapters) {
    ArrayNode out = json.createArrayNode();
    for (SourceInstance instance : instances) {
      Optional<SourceAdapter> adapter = adapters.forKind(instance.kind());
      ObjectNode item = out.addObject();
      item.put("id", instance.id().value());
      item.put("kind", instance.kind());
      item.put("displayName", instance.displayName());
      item.put("enabled", instance.enabled());
      item.put(
          "collectionCoverage",
          adapter
              .map(a -> a.coverage(instance.config(), CollectionMode.INCREMENTAL, Optional.empty()))
              .map(c -> c.wire())
              .orElse("incremental"));
      item.put(
          "deletionDetection",
          adapter.map(a -> a.deletionDetection(instance.config()).wire()).orElse("none"));
      item.put("updatedAt", iso(instance.updatedAt()));
      adapter.ifPresent(
          a ->
              a.listingFields(instance.config())
                  .forEach(field -> item.set(field.name(), listingValue(instance, field))));
    }
    return out;
  }

  /** 어댑터가 낸 목록 필드 값(JSON 원문)을 읽는다. JSON 값 하나가 아니면 목록을 거부한다 — 깨진 항목을 내보내지 않는다. */
  private JsonNode listingValue(SourceInstance instance, ListingField field) {
    JsonNode value;
    try {
      value = json.readTree(field.valueJson());
    } catch (JacksonException e) {
      throw notJson(instance, field, e);
    }
    if (value == null || value.isMissingNode()) throw notJson(instance, field, null);
    return value;
  }

  private static IllegalStateException notJson(
      SourceInstance instance, ListingField field, @Nullable Exception cause) {
    return new IllegalStateException(
        "listing field "
            + field.name()
            + " of source adapter "
            + instance.kind()
            + " is not a JSON value",
        cause);
  }

  /** 소스 상태. 없는 소스는 null. */
  JsonNode status(Optional<SourceQueries.SourceStatus> status) {
    return status.<JsonNode>map(this::status).orElse(json.nullNode());
  }

  JsonNode status(
      Optional<SourceQueries.SourceStatus> status, Optional<SourceQueries.AccessStatus> access) {
    return status
        .<JsonNode>map(
            value -> {
              ObjectNode out = status(value);
              access.ifPresent(a -> out.set("access", accessStatus(a)));
              return out;
            })
        .orElse(json.nullNode());
  }

  JsonNode accessExplanation(Optional<AccessExplainer.Result> result) {
    if (result.isEmpty()) return json.nullNode();
    var value = result.orElseThrow();
    var document = value.document();
    ObjectNode out = json.createObjectNode();
    out.put("documentId", document.documentId());
    out.put("sourceInstanceId", document.source().value());
    out.put("spaceId", document.space().value());
    out.set("acl", documentAccess(document.acl()));
    var carriers = out.putArray("carriers");
    document.carriers().forEach(carrier -> carriers.add(rawAccess(carrier)));
    value
        .user()
        .ifPresent(
            user -> {
              var node = out.putObject("user");
              node.put("id", user.id());
              node.put("known", user.known());
              node.set("keys", accessKeys(user.keys()));
              node.set("intersection", accessKeys(user.intersection()));
              node.put("visible", user.visible());
            });
    return out;
  }

  private ObjectNode documentAccess(Optional<DocumentAcl> acl) {
    var out = json.createObjectNode();
    if (acl.isEmpty()) {
      out.put("status", "missing");
      out.put("reason", "acl_missing");
      out.set("keys", accessKeys(Set.of()));
      return out;
    }
    var value = acl.orElseThrow();
    out.put("status", value.status().name().toLowerCase(Locale.ROOT));
    out.set("keys", accessKeys(value.keys()));
    out.put("decidedAt", iso(value.observedAt()));
    out.put("reason", value.reason().wire());
    return out;
  }

  private ObjectNode rawAccess(SourceQueries.RawAccess carrier) {
    var out = json.createObjectNode();
    out.put("externalId", carrier.externalId());
    if (carrier.acl() instanceof RawAcl.Observed observed) {
      out.put("status", observed.status().name().toLowerCase(Locale.ROOT));
      out.set("keys", accessKeys(observed.keys()));
      out.put("observedAt", iso(observed.observedAt()));
      out.put("reason", observed.reason().wire());
    } else {
      out.put("status", "not_observed");
      out.put("reason", "unobserved_carrier");
    }
    return out;
  }

  private JsonNode accessKeys(Set<AccessKey> keys) {
    return json.valueToTree(keys.stream().map(AccessKey::toString).sorted().toList());
  }

  ObjectNode accessStatus(SourceQueries.AccessStatus status) {
    ObjectNode out = json.createObjectNode();
    if (status.running()) out.put("result", "running");
    else status.result().ifPresent(result -> out.put("result", result.wire()));
    status.finishedAt().ifPresent(at -> out.put("finishedAt", iso(at)));
    out.put("consecutiveIncomplete", status.consecutiveIncomplete());
    status.lastCompleteAt().ifPresent(at -> out.put("lastCompleteAt", iso(at)));
    out.put("documentsHidden", status.documentsHidden());
    out.put("aclUnreadable", status.aclUnreadable());
    out.set(
        "aclUnreadableFiles",
        json.valueToTree(status.unreadableFiles().stream().sorted().toList()));
    out.put("rawUnknown", status.rawUnknown());
    out.put("rawFailed", status.rawFailed());
    out.put("documentsMissingAcl", status.documentsMissingAcl());
    out.put("rawUnobserved", status.rawUnobserved());
    out.put("rawStale", status.rawStale());
    status.oldestObservedAt().ifPresent(at -> out.put("oldestObservedAt", iso(at)));
    status.cost().ifPresent(cost -> accessCost(out.putObject("cost"), cost));
    return out;
  }

  private static void accessCost(ObjectNode out, SourceQueries.RunCost run) {
    out.put("runId", run.runId().value());
    out.put("recordedAt", iso(run.recordedAt()));
    var cost = run.cost();
    out.put("targetCount", cost.targetCount());
    cost.callCount()
        .ifPresentOrElse(value -> out.put("callCount", value), () -> out.putNull("callCount"));
    out.put("elapsedMs", cost.elapsedMs());
  }

  ObjectNode status(SourceQueries.SourceStatus s) {
    ObjectNode out = json.createObjectNode();
    out.put("sourceInstanceId", s.source().value());
    out.put("enabled", s.enabled());
    out.put("running", s.running());
    out.put("currentItemCount", s.currentItemCount());
    out.put("tombstoneCount", s.tombstoneCount());
    out.put("recentFailureCount", s.recentFailureCount());
    if (s.ownershipSkipped() > 0) out.put("ownershipSkipped", s.ownershipSkipped());
    events(out.putObject("events"));
    s.activeLeaseExpiresAt().ifPresent(at -> out.put("activeLeaseExpiresAt", iso(at)));
    s.lastCompleteSnapshot()
        .ifPresent(
            snapshot -> {
              out.put("lastCompleteSnapshotAt", iso(snapshot.completedAt()));
              out.put("lastCompleteSnapshotDeletionCount", snapshot.deletionCount());
            });
    s.lastRun()
        .ifPresent(
            run -> {
              out.put("lastAttemptAt", iso(run.startedAt()));
              out.put("lastRunStatus", run.status().wire());
              run.errorKind().ifPresent(kind -> out.put("lastRunErrorKind", kind));
              run.errorSummary().ifPresent(summary -> out.put("lastRunErrorSummary", summary));
            });
    s.lastSuccessAt().ifPresent(at -> out.put("lastSuccessAt", iso(at)));
    // 스냅샷 축소 보호 키(ADR-0028) — 보호 중이거나 허용 대기 중일 때만 나온다
    s.reconcileGuard()
        .ifPresent(guard -> reconcileGuard(out.putObject("snapshotReconcileGuard"), guard));
    s.shrinkAcceptedAt().ifPresent(at -> out.put("shrinkAcceptedAt", iso(at)));
    // 소스별 기준(등록값)이 하나라도 있을 때만 — 없으면 데몬 기본값이다
    if (!s.shrinkGuard().equals(SnapshotShrinkOverrides.DAEMON_DEFAULTS))
      guardLimits(out.putObject("snapshotGuardLimits"), s.shrinkGuard());
    return out;
  }

  /** Slack 이벤트 수신함은 옮기지 않았다(ADR-0024 §2) — 개수는 늘 0이다. */
  private static void events(ObjectNode out) {
    out.put("pendingCount", 0);
    out.put("processingCount", 0);
    out.put("rejectedCount", 0);
  }

  private static void reconcileGuard(ObjectNode out, SnapshotGuardStore.Guarded guard) {
    out.put("runId", guard.run().value());
    out.put("guardedAt", iso(guard.guardedAt()));
    out.put("unobservedCount", guard.unobservedCount());
    out.put("activeCount", guard.activeCount());
    out.put("consecutiveCount", guard.consecutiveCount());
    out.put("repeated", guard.consecutiveCount() >= SnapshotShrinkGuard.REPEATED_AFTER);
  }

  private static void guardLimits(ObjectNode out, SnapshotShrinkOverrides limits) {
    limits.minActive().ifPresent(v -> out.put("minActive", v));
    limits.maxDeletePercent().ifPresent(v -> out.put("maxDeletePercent", v));
  }

  /** CLI 한 줄: 격리 원문(quarantinedContent)은 뺀다(크고 민감할 수 있다). */
  ObjectNode rejection(SourceQueries.RecentRejection entry) {
    ObjectNode out = json.createObjectNode();
    out.put("runId", entry.runId().value());
    out.put("capturedAt", iso(entry.capturedAt()));
    ObjectNode rejection = out.putObject("rejection");
    entry
        .rejection()
        .providerLocatorJson()
        .ifPresent(locator -> rejection.set("providerLocator", json.readTree(locator)));
    rejection.put("observedAt", iso(entry.rejection().observedAt()));
    rejection.put("failureKind", entry.rejection().failureKind().wire());
    rejection.put("message", entry.rejection().message());
    return out;
  }

  /** 원문 목록 한 페이지: {items, nextCursor?}. */
  ObjectNode page(SourceQueries.RawCatalogPage page) {
    ObjectNode out = json.createObjectNode();
    ArrayNode items = out.putArray("items");
    page.items().forEach(entry -> items.add(record(entry)));
    page.nextCursor().ifPresent(cursor -> out.put("nextCursor", cursor.encode()));
    return out;
  }

  JsonNode record(Optional<SourceQueries.RawEntry> entry) {
    return entry.<JsonNode>map(this::record).orElse(json.nullNode());
  }

  /** active는 {state, item}, deleted는 {state, key, …}. */
  ObjectNode record(SourceQueries.RawEntry entry) {
    ObjectNode out = json.createObjectNode();
    switch (entry.record()) {
      case RawRecord.Active active -> {
        out.put("state", "active");
        item(out.putObject("item"), entry.source(), active.item());
      }
      case RawRecord.Deleted deleted -> {
        out.put("state", "deleted");
        key(out, entry.source(), deleted.externalId());
        deleted.resourceType().ifPresent(type -> out.put("resourceType", type));
        deleted.lastSourceVersion().ifPresent(v -> out.put("lastSourceVersion", v));
        deleted.sourceOrder().ifPresent(order -> order(out, order));
        out.put("observedAt", iso(deleted.observedAt()));
        out.put("deletedAt", iso(deleted.deletedAt()));
      }
    }
    return out;
  }

  /** 운영 개요(`status`, status.sh 대체): 소스별 상태·최근 run·점유·공간별 최신성과 임베딩 세대·로그 경고. */
  ObjectNode overview(
      Instant now,
      List<SourceQueries.SourceStatus> statuses,
      List<CollectionRun> runs,
      List<WorkClaim> claims,
      List<SpaceOverview> freshness,
      Optional<LogProblems> log) {
    ObjectNode out = json.createObjectNode();
    out.put("now", iso(now));
    ArrayNode sources = out.putArray("sources");
    statuses.forEach(s -> sources.add(status(s)));
    ArrayNode recent = out.putArray("recentRuns");
    runs.forEach(run -> recentRun(recent.addObject(), run));
    ArrayNode leases = out.putArray("leases");
    claims.forEach(claim -> lease(leases.addObject(), claim, now));
    ArrayNode spaces = out.putArray("freshness");
    freshness.forEach(space -> spaceOverview(spaces.addObject(), space));
    log.ifPresent(problems -> logProblems(out.putObject("log"), problems));
    return out;
  }

  private static void recentRun(ObjectNode out, CollectionRun run) {
    out.put("runId", run.id().value());
    out.put("sourceInstanceId", run.source().value());
    out.put("status", run.status().wire());
    out.put("startedAt", iso(run.startedAt()));
    run.finishedAt().ifPresent(at -> out.put("finishedAt", iso(at)));
    out.put("pagesApplied", run.pagesApplied());
    out.put("upserted", run.upserted());
    out.put("deleted", run.deleted());
    out.put("rejected", run.rejected());
    run.errorKind().ifPresent(kind -> out.put("errorKind", kind));
    run.errorSummary().ifPresent(summary -> out.put("errorSummary", summary));
  }

  private static void lease(ObjectNode out, WorkClaim claim, Instant now) {
    out.put("sourceInstanceId", claim.source().value());
    out.put("ownerId", claim.owner().value());
    out.put("fencingToken", claim.fencingToken());
    out.put("expiresAt", iso(claim.expiresAt()));
    out.put("active", claim.isActiveAt(now));
  }

  /** 공간 하나: 최신성(모르면 null 키)과 임베딩 세대(저장된 것이 없으면 null). */
  private static void spaceOverview(ObjectNode out, SpaceOverview space) {
    out.put("space", space.space().value());
    Freshness value = space.freshness();
    putOrNull(out, "latestCapturedAt", value.latestCapturedAt());
    value
        .staleHours()
        .ifPresentOrElse(h -> out.put("staleHours", h), () -> out.putNull("staleHours"));
    out.put("stale", value.stale());
    ObjectNode generation = out.putObject("embeddingGeneration");
    putOrNull(generation, "stored", space.storedGeneration());
    generation.put("configured", space.configuredGeneration().value());
  }

  private static void logProblems(ObjectNode out, LogProblems problems) {
    out.put("path", problems.path());
    out.put("found", problems.found());
    ArrayNode lines = out.putArray("problems");
    problems.lines().forEach(lines::add);
  }

  private static void putOrNull(ObjectNode out, String key, Optional<String> value) {
    value.ifPresentOrElse(v -> out.put(key, v), () -> out.putNull(key));
  }

  /**
   * 공간 하나의 개요: 최신성과 임베딩 세대(stage2 §3.3).
   *
   * @param storedGeneration 공간에 저장된 세대({@code pending:} 표시 포함), 아직 임베딩하지 않은 공간이면 empty
   * @param configuredGeneration 설정 임베더의 세대
   */
  record SpaceOverview(
      SpaceId space,
      Freshness freshness,
      Optional<String> storedGeneration,
      EmbeddingGeneration configuredGeneration) {}

  /** 로그 파일 끝의 warn·error 줄(found = 파일을 읽었다). */
  record LogProblems(String path, boolean found, List<String> lines) {}

  private void item(ObjectNode out, SourceInstanceId source, RawItem item) {
    key(out, source, item.externalId());
    out.put("resourceType", item.resourceType());
    out.put("sourceVersion", item.sourceVersion());
    item.sourceOrder().ifPresent(order -> order(out, order));
    item.sourceCreatedAt().ifPresent(at -> out.put("sourceCreatedAt", at));
    item.sourceUpdatedAt().ifPresent(at -> out.put("sourceUpdatedAt", at));
    out.put("observedAt", iso(item.observedAt()));
    out.set("metadata", json.readTree(item.metadataJson()));
    item.content().ifPresent(content -> out.set("content", content(content)));
  }

  /**
   * 원문 내용: inline {storage, mediaType, value}, blob {storage, mediaType, blobKey, sha256, size}.
   */
  private ObjectNode content(RawContent content) {
    ObjectNode out = json.createObjectNode();
    switch (content) {
      case RawContent.Inline inline -> {
        out.put("storage", "inline");
        out.put("mediaType", inline.mediaType());
        out.set("value", json.readTree(inline.valueJson()));
      }
      case RawContent.Blob blob -> {
        out.put("storage", "blob");
        out.put("mediaType", blob.mediaType());
        // FsBlobStore 키 형식(ADR-0024 §2): 저장소는 raw_blobs.sha256으로 찾지만 출력 형식은 그대로 둔다
        out.put("blobKey", "sha256/" + blob.sha256());
        out.put("sha256", blob.sha256());
        out.put("size", blob.size());
      }
    }
    return out;
  }

  private static void key(ObjectNode out, SourceInstanceId source, String externalId) {
    ObjectNode key = out.putObject("key");
    key.put("sourceInstanceId", source.value());
    key.put("externalId", externalId);
  }

  private static void order(ObjectNode out, SourceOrder order) {
    ObjectNode o = out.putObject("sourceOrder");
    o.put("scheme", order.scheme());
    o.put("position", order.position());
  }

  static String iso(Instant at) {
    return ISO_MILLIS.format(at);
  }
}
