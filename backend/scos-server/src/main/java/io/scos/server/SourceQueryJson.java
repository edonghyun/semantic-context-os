package io.scos.server;

import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SnapshotShrinkOverrides;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.WorkClaim;
import io.scos.engine.Freshness;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.ListingField;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.store.SpaceId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 조회 결과 → TS 조회 CLI와 같은 키 순서의 JSON(TS
 * source-list·SourceStatus·RecentRejection·RawSourceRecord·RawItemPage). 없는 선택 값은 키째 뺀다(TS
 * undefined). 시각은 toISOString 형식(UTC 밀리초). 어댑터 소유 JSON(metadata·content·locator)은 저장된 원문을 읽어 키 순서를
 * 그대로 둔다.
 */
final class SourceQueryJson {
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final ObjectMapper json;

  SourceQueryJson(ObjectMapper json) {
    this.json = json;
  }

  /** TS listSources: 어댑터가 없으면 incremental·none. coverage는 모드·체크포인트 없이 묻는다. */
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
      SourceInstance instance, ListingField field, Exception cause) {
    return new IllegalStateException(
        "listing field "
            + field.name()
            + " of source adapter "
            + instance.kind()
            + " is not a JSON value",
        cause);
  }

  /** TS SourceStatus. 없는 소스는 null. */
  JsonNode status(Optional<SourceQueries.SourceStatus> status) {
    return status.<JsonNode>map(this::status).orElse(json.nullNode());
  }

  ObjectNode status(SourceQueries.SourceStatus s) {
    ObjectNode out = json.createObjectNode();
    out.put("sourceInstanceId", s.source().value());
    out.put("enabled", s.enabled());
    out.put("running", s.running());
    out.put("currentItemCount", s.currentItemCount());
    out.put("tombstoneCount", s.tombstoneCount());
    out.put("recentFailureCount", s.recentFailureCount());
    // Slack 이벤트 수신함은 포팅 범위 밖이라 늘 0이다(ADR-0024 §2)
    ObjectNode events = out.putObject("events");
    events.put("pendingCount", 0);
    events.put("processingCount", 0);
    events.put("rejectedCount", 0);
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
    // TS에 없는 키(스냅샷 축소 보호, ADR-0028) — 보호 중이거나 허용 대기 중일 때만 나온다
    s.reconcileGuard()
        .ifPresent(
            guard -> {
              ObjectNode g = out.putObject("snapshotReconcileGuard");
              g.put("runId", guard.run().value());
              g.put("guardedAt", iso(guard.guardedAt()));
              g.put("unobservedCount", guard.unobservedCount());
              g.put("activeCount", guard.activeCount());
              g.put("consecutiveCount", guard.consecutiveCount());
              g.put("repeated", guard.consecutiveCount() >= SnapshotShrinkGuard.REPEATED_AFTER);
            });
    s.shrinkAcceptedAt().ifPresent(at -> out.put("shrinkAcceptedAt", iso(at)));
    // 소스별 기준(등록값)이 하나라도 있을 때만 — 없으면 데몬 기본값이다
    if (!s.shrinkGuard().equals(SnapshotShrinkOverrides.DAEMON_DEFAULTS)) {
      ObjectNode limits = out.putObject("snapshotGuardLimits");
      s.shrinkGuard().minActive().ifPresent(v -> limits.put("minActive", v));
      s.shrinkGuard().maxDeletePercent().ifPresent(v -> limits.put("maxDeletePercent", v));
    }
    return out;
  }

  /** CLI 한 줄: 격리 원문(quarantinedContent)은 뺀다(크고 민감할 수 있다 — TS와 같다). */
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

  /** TS RawItemPage: {items, nextCursor?}. */
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

  /** TS toRawSourceRecord: active는 {state, item}, deleted는 {state, key, …}. */
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

  /** 운영 개요(`status`, status.sh 대체): 소스별 상태·최근 run·점유·공간별 최신성·로그 경고. */
  ObjectNode overview(
      Instant now,
      List<SourceQueries.SourceStatus> statuses,
      List<CollectionRun> runs,
      List<WorkClaim> claims,
      List<SpaceFreshness> freshness,
      Optional<LogProblems> log) {
    ObjectNode out = json.createObjectNode();
    out.put("now", iso(now));
    ArrayNode sources = out.putArray("sources");
    statuses.forEach(s -> sources.add(status(s)));
    ArrayNode recent = out.putArray("recentRuns");
    for (CollectionRun run : runs) {
      ObjectNode r = recent.addObject();
      r.put("runId", run.id().value());
      r.put("sourceInstanceId", run.source().value());
      r.put("status", run.status().wire());
      r.put("startedAt", iso(run.startedAt()));
      run.finishedAt().ifPresent(at -> r.put("finishedAt", iso(at)));
      r.put("pagesApplied", run.pagesApplied());
      r.put("upserted", run.upserted());
      r.put("deleted", run.deleted());
      r.put("rejected", run.rejected());
      run.errorKind().ifPresent(kind -> r.put("errorKind", kind));
      run.errorSummary().ifPresent(summary -> r.put("errorSummary", summary));
    }
    ArrayNode leases = out.putArray("leases");
    for (WorkClaim claim : claims) {
      ObjectNode l = leases.addObject();
      l.put("sourceInstanceId", claim.source().value());
      l.put("ownerId", claim.owner().value());
      l.put("fencingToken", claim.fencingToken());
      l.put("expiresAt", iso(claim.expiresAt()));
      l.put("active", claim.isActiveAt(now));
    }
    ArrayNode spaces = out.putArray("freshness");
    for (SpaceFreshness space : freshness) {
      ObjectNode f = spaces.addObject();
      f.put("space", space.space().value());
      Freshness value = space.freshness();
      if (value.latestCapturedAt().isPresent())
        f.put("latestCapturedAt", value.latestCapturedAt().get());
      else f.putNull("latestCapturedAt");
      if (value.staleHours().isPresent()) f.put("staleHours", value.staleHours().get());
      else f.putNull("staleHours");
      f.put("stale", value.stale());
    }
    log.ifPresent(
        problems -> {
          ObjectNode l = out.putObject("log");
          l.put("path", problems.path());
          l.put("found", problems.found());
          ArrayNode lines = l.putArray("problems");
          problems.lines().forEach(lines::add);
        });
    return out;
  }

  record SpaceFreshness(SpaceId space, Freshness freshness) {}

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
   * TS RawContent: inline {storage, mediaType, value}, blob {storage, mediaType, blobKey, sha256,
   * size}.
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
        // TS FsBlobStore 키 형식(ADR-0024 §2): Java는 raw_blobs.sha256으로 찾지만 출력은 TS와 같게 둔다
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
