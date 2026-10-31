package io.scos.connector.notion.internal;

import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.engine.support.call.WorkQueue;
import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionState;
import io.scos.spi.connector.ReconciliationContext;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** roots 재조정의 확정 확인점·미완료 진행·run 계획을 소유한다. */
public final class NotionReconciliationState {
  static final int SCHEMA_VERSION = 5;

  private record SearchRetry(NotionWork work, long enteredGeneration) {}

  record Scan(
      Instant startedAt,
      Instant lowerBound,
      Duration overlap,
      Optional<String> cursor,
      boolean listingComplete,
      List<NotionWork> work) {
    Scan {
      work = List.copyOf(work);
    }
  }

  private record BodyProgress(
      String pageId,
      String version,
      List<NotionWork.BlockChildren> work,
      List<NotionWork> discovered) {
    BodyProgress {
      work = List.copyOf(work);
      discovered = List.copyOf(discovered);
    }
  }

  record RowScan(
      Instant startedAt,
      NotionPageRefresh.Path path,
      Optional<Instant> lowerBound,
      Duration overlap,
      Optional<Instant> createdFrom,
      Optional<Instant> lastCreated,
      boolean limited,
      boolean listingComplete,
      Optional<String> cursor,
      List<NotionWork> work) {
    RowScan {
      work = List.copyOf(work);
    }
  }

  private static final class Container {
    final String id;
    final NotionMutations.Resource kind;
    final Instant baselineStartedAt;
    Optional<String> databaseId = Optional.empty();
    Optional<Instant> watermark = Optional.empty();
    Optional<Instant> completedAt = Optional.empty();
    Optional<NotionPageRefresh.Path> watermarkPath = Optional.empty();
    final Map<NotionPageRefresh.Path, RowScan> scans = new EnumMap<>(NotionPageRefresh.Path.class);

    Container(String id, NotionMutations.Resource kind, Instant baselineStartedAt) {
      this.id = id;
      this.kind = kind;
      this.baselineStartedAt = baselineStartedAt;
    }
  }

  private final Set<String> fullRowOwners = new HashSet<>();
  private final Set<String> deferredWork = new HashSet<>();
  private final Map<String, String> observedVersions = new LinkedHashMap<>();
  private final Map<String, Container> containers = new LinkedHashMap<>();
  private final Map<String, BodyProgress> refreshes = new LinkedHashMap<>();
  private final Map<String, BodyProgress> handoffs = new LinkedHashMap<>();
  private final Instant baselineStartedAt;
  private Instant nextIncrementalAt;
  private long scopeEpoch;
  private long fullGeneration;
  private boolean baselineIncomplete = true;
  private Optional<Instant> incrementalStartedAt = Optional.empty();
  private final ObjectMapper json;
  private final Roots roots;
  private final Map<SearchPhase, List<SearchRetry>> searchRetries =
      new EnumMap<>(SearchPhase.class);
  private final Map<SearchPhase, Scan> scans = new EnumMap<>(SearchPhase.class);
  private final Map<SearchPhase, Instant> watermarks = new EnumMap<>(SearchPhase.class);
  private final Map<SearchPhase, Instant> explorationCompletions = new EnumMap<>(SearchPhase.class);
  private Optional<Checkpoint> full = Optional.empty();
  private CollectionCoverage coverage = CollectionCoverage.INCREMENTAL;

  private NotionReconciliationState(ObjectMapper json, Roots roots, Instant baselineStartedAt) {
    this.json = json;
    this.roots = roots;
    this.baselineStartedAt = baselineStartedAt;
    this.nextIncrementalAt = baselineStartedAt;
  }

  public static NotionReconciliationState read(
      ObjectMapper json,
      Roots roots,
      CollectionMode mode,
      Optional<Checkpoint> checkpoint,
      Instant now) {
    try {
      if (checkpoint.isPresent() && checkpoint.get().schemaVersion() > SCHEMA_VERSION)
        throw NotionProtocolException.invalidCheckpoint();
      if (checkpoint.isEmpty() || checkpoint.get().schemaVersion() < 4) {
        return fromLegacy(json, roots, mode, checkpoint, now);
      }
      var value = json.readTree(checkpoint.get().valueJson());
      validateEnvelope(value);
      requireMatchingScope(value, json, roots);
      var state =
          new NotionReconciliationState(
              json, roots, Instant.parse(value.at("/progress/baselineStartedAt").asString()));
      state.fullGeneration = readGeneration(value.path("progress"), "fullGeneration");
      state.baselineIncomplete = value.at("/progress/baselineIncomplete").asBoolean();
      state.nextIncrementalAt = Instant.parse(value.at("/progress/nextIncrementalAt").asString());
      var previousInterval = savedInterval(value.path("progress"));
      if (state.nextIncrementalAt.isAfter(state.baselineStartedAt))
        state.nextIncrementalAt =
            state.nextIncrementalAt.minus(previousInterval).plus(roots.incrementalInterval());
      state.incrementalStartedAt = savedInstant(value.path("progress"), "incrementalStartedAt");
      if (value.has("traversal"))
        state.full = Optional.of(new Checkpoint(3, JsJson.stringify(value.get("traversal"))));
      state.full.ifPresent(saved -> NotionRootTraversal.resume(json, roots, Optional.of(saved)));
      state.restoreSearches(value, now);
      state.restoreContainers(value, now);
      state.restoreHandoffs(value);
      if (mode == CollectionMode.SNAPSHOT) {
        state
            .containers
            .values()
            .forEach(container -> container.scans.remove(NotionPageRefresh.Path.FULL));
        state.full =
            Optional.of(NotionRootTraversal.resume(json, roots, Optional.empty()).checkpoint());
        state.coverage = CollectionCoverage.COMPLETE_SNAPSHOT;
        state.fullGeneration++;
      }
      return state;
    } catch (JacksonException | DateTimeParseException malformed) {
      throw NotionProtocolException.invalidCheckpoint();
    }
  }

  private static void requireMatchingScope(JsonNode value, ObjectMapper json, Roots roots) {
    if (!value.at("/scope/rootPageIds").equals(json.valueToTree(roots.rootPageIds()))
        || value.at("/scope/discoverSharedPages").asBoolean() != roots.discoverSharedPages())
      throw new NotionProtocolException(NotionProtocolException.ROOTS_CHANGED);
    ObjectNode expectedScope = NotionScopeIdentity.json(json, roots);
    if (!value
        .at("/scope/configFingerprint")
        .asString()
        .equals(Text.sha256Hex(JsJson.stringify(expectedScope))))
      throw NotionProtocolException.invalidCheckpoint();
  }

  private static NotionReconciliationState fromLegacy(
      ObjectMapper json,
      Roots roots,
      CollectionMode mode,
      Optional<Checkpoint> checkpoint,
      Instant now) {
    var state = new NotionReconciliationState(json, roots, now);
    if (checkpoint.isPresent() && checkpoint.get().schemaVersion() == 3) {
      var traversal = NotionRootTraversal.resume(json, roots, checkpoint);
      state.full = checkpoint;
      if (traversal.startsCycle()) state.coverage = CollectionCoverage.COMPLETE_SNAPSHOT;
    } else {
      state.full =
          Optional.of(NotionRootTraversal.resume(json, roots, Optional.empty()).checkpoint());
      state.coverage = CollectionCoverage.COMPLETE_SNAPSHOT;
    }
    if (mode == CollectionMode.SNAPSHOT) {
      state.full =
          Optional.of(NotionRootTraversal.resume(json, roots, Optional.empty()).checkpoint());
      state.coverage = CollectionCoverage.COMPLETE_SNAPSHOT;
    }
    if (state.coverage == CollectionCoverage.COMPLETE_SNAPSHOT) state.fullGeneration++;
    return state;
  }

  private void restoreSearches(JsonNode value, Instant now) {
    for (SearchPhase phase : SearchPhase.values()) {
      var watermark = value.at("/incremental/" + phase.wire() + "/committedWatermark");
      if (watermark.isString()) watermarks.put(phase, Instant.parse(watermark.asString()));
      savedInstant(value.at("/incremental/" + phase.wire()), "completedAt")
          .ifPresent(at -> explorationCompletions.put(phase, at));
      restoreSearchRetries(phase, value.at("/incremental/" + phase.wire() + "/retryWork"));
      var scan = value.at("/incremental/" + phase.wire() + "/scan");
      if (scan.isObject()) {
        var work = new ArrayList<NotionWork>();
        scan.path("work").forEach(w -> work.add(NotionWork.read(w)));
        scans.put(
            phase,
            new Scan(
                scan.path("listingComplete").asBoolean() && !work.isEmpty()
                    ? now
                    : Instant.parse(scan.path("startedAt").asString()),
                Instant.parse(scan.path("lowerBound").asString()),
                savedOverlap(scan),
                scan.path("cursor").isString()
                    ? Optional.of(scan.path("cursor").asString())
                    : Optional.empty(),
                scan.path("listingComplete").asBoolean() && work.isEmpty(),
                work));
        if (!scans
            .get(phase)
            .lowerBound()
            .equals(watermarks.getOrDefault(phase, baselineStartedAt).minus(savedOverlap(scan))))
          throw NotionProtocolException.invalidCheckpoint();
      }
    }
  }

  private void restoreSearchRetries(SearchPhase phase, JsonNode saved) {
    var retries = new ArrayList<SearchRetry>();
    saved.forEach(
        w -> {
          var retry =
              w.has("work")
                  ? new SearchRetry(
                      NotionWork.read(w.path("work")), readGeneration(w, "enteredGeneration"))
                  : new SearchRetry(NotionWork.read(w), fullGeneration);
          if (retry.enteredGeneration() > fullGeneration)
            throw NotionProtocolException.invalidCheckpoint();
          retries.add(retry);
        });
    if (!retries.isEmpty()) searchRetries.put(phase, retries);
  }

  private void restoreContainers(JsonNode value, Instant now) {
    for (JsonNode saved : value.at("/incremental/containers")) {
      Container container = restoreContainer(saved, now);
      if (containers.put(container.kind + ":" + container.id, container) != null)
        throw NotionProtocolException.invalidCheckpoint();
    }
  }

  private Container restoreContainer(JsonNode saved, Instant now) {
    NotionMutations.Resource kind = containerKind(saved);
    var container =
        new Container(
            saved.path("id").asString(),
            kind,
            Instant.parse(saved.path("baselineStartedAt").asString()));
    if (saved.has("databaseId")) {
      if (kind != NotionMutations.Resource.DATA_SOURCE
          || !NotionObjects.nonEmptyString(saved.get("databaseId")))
        throw NotionProtocolException.invalidCheckpoint();
      container.databaseId = Optional.of(saved.path("databaseId").asString());
    }
    if (saved.has("committedWatermark"))
      container.watermark = Optional.of(Instant.parse(saved.path("committedWatermark").asString()));
    container.completedAt = savedInstant(saved, "completedAt");
    if (saved.has("watermarkPath"))
      container.watermarkPath =
          Optional.of(NotionPageRefresh.Path.valueOf(saved.path("watermarkPath").asString()));
    restoreRows(container, saved, now);
    if (container.kind == NotionMutations.Resource.DATABASE
        && (container.watermark.isPresent() || !container.scans.isEmpty()))
      throw NotionProtocolException.invalidCheckpoint();
    return container;
  }

  private static NotionMutations.Resource containerKind(JsonNode saved) {
    fields(
        saved,
        Set.of("id", "kind", "baselineStartedAt"),
        Set.of(
            "committedWatermark",
            "completedAt",
            "watermarkPath",
            "scan",
            "fullScan",
            "databaseId"));
    if (!NotionObjects.nonEmptyString(saved.get("id")))
      throw NotionProtocolException.invalidCheckpoint();
    NotionMutations.Resource kind;
    try {
      kind = NotionMutations.Resource.valueOf(saved.path("kind").asString());
    } catch (IllegalArgumentException invalid) {
      throw NotionProtocolException.invalidCheckpoint();
    }
    if (kind != NotionMutations.Resource.DATABASE && kind != NotionMutations.Resource.DATA_SOURCE)
      throw NotionProtocolException.invalidCheckpoint();
    return kind;
  }

  private void restoreRows(Container container, JsonNode saved, Instant now) {
    for (NotionPageRefresh.Path path : NotionPageRefresh.Path.values()) {
      String field = path == NotionPageRefresh.Path.FULL ? "fullScan" : "scan";
      if (!saved.has(field)) continue;
      RowScan pending = readRows(saved.get(field));
      if ((!saved.get(field).has("listingComplete") && pending.cursor().isEmpty())
          || (pending.listingComplete() && !pending.work().isEmpty()))
        pending =
            new RowScan(
                now,
                pending.path(),
                pending.lowerBound(),
                pending.overlap(),
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                pending.work());
      if (pending.path() != path) throw NotionProtocolException.invalidCheckpoint();
      var expectedLower =
          path == NotionPageRefresh.Path.INCREMENTAL
              ? container.watermark.map(w -> w.minus(savedOverlap(saved.get(field))))
              : Optional.<Instant>empty();
      if (!pending.lowerBound().equals(expectedLower))
        throw NotionProtocolException.invalidCheckpoint();
      if (pending.createdFrom().isPresent()
          && pending.lastCreated().isPresent()
          && pending.lastCreated().get().isBefore(pending.createdFrom().get()))
        throw NotionProtocolException.invalidCheckpoint();
      container.scans.put(path, pending);
    }
  }

  private void restoreHandoffs(JsonNode value) {
    for (JsonNode body : value.path("refreshes")) {
      if ((body.path("work").isEmpty() || body.has("discoveredVersion"))
          && !body.path("discovered").isEmpty()) {
        var discovered = new ArrayList<NotionWork>();
        body.path("discovered").forEach(step -> discovered.add(NotionWork.read(step)));
        handoffs.put(
            NotionObjects.idKey(body.path("pageId").asString()),
            new BodyProgress(
                body.path("pageId").asString(),
                body.path(body.has("discoveredVersion") ? "discoveredVersion" : "version")
                    .asString(),
                List.of(),
                discovered));
      }
    }
  }

  void recordBodyProgress(String pageId, String version, List<NotionWork.BlockChildren> work) {
    refreshes.put(NotionObjects.idKey(pageId), new BodyProgress(pageId, version, work, List.of()));
  }

  void bodyFinished(String pageId, String version, List<NotionWork> discovered) {
    refreshes.remove(NotionObjects.idKey(pageId));
    if (discovered.isEmpty()) handoffs.remove(NotionObjects.idKey(pageId));
    else
      handoffs.put(
          NotionObjects.idKey(pageId), new BodyProgress(pageId, version, List.of(), discovered));
  }

  void acknowledgeBody(String pageId, String version) {
    handoffs.computeIfPresent(
        NotionObjects.idKey(pageId),
        (id, handoff) -> handoff.version().equals(version) ? null : handoff);
  }

  List<NotionWork> bodyHandoff(String pageId, String version) {
    BodyProgress body = handoffs.get(NotionObjects.idKey(pageId));
    return body != null && body.version().equals(version) && body.work().isEmpty()
        ? body.discovered()
        : List.of();
  }

  String fingerprint() {
    return Text.sha256Hex(JsJson.stringify(NotionScopeIdentity.json(json, roots)));
  }

  void checkBaseline(CollectionState committed, CancellationToken cancel) {
    baselineIncomplete = !committed.reconciliationBaseline(fingerprint(), cancel);
  }

  ReconciliationContext reconciliation(JsonNode page, NotionPageRefresh.Path path) {
    boolean row = page.at("/parent/type").asString().equals("data_source_id");
    Optional<Instant> previous =
        row
            ? successfulRowExploration(page.at("/parent/data_source_id").asString())
            : watermark(page.path("in_trash").asBoolean() ? SearchPhase.TRASH : SearchPhase.ACTIVE);
    return new ReconciliationContext(
        path == NotionPageRefresh.Path.FULL
            ? ReconciliationContext.Path.FULL
            : ReconciliationContext.Path.INCREMENTAL,
        previous,
        Optional.of(NotionMutations.timestamp(page.path("last_edited_time").asString())),
        baselineIncomplete || (row && previous.isEmpty()),
        roots.overlap());
  }

  Roots roots() {
    return roots;
  }

  public Checkpoint checkpoint() {
    ObjectNode value = json.createObjectNode();
    ObjectNode scope = NotionScopeIdentity.json(json, roots);
    scope.put("configFingerprint", fingerprint());
    value.set("scope", scope);
    ObjectNode incremental = value.putObject("incremental");
    saveSearches(incremental);
    saveContainers(incremental);
    full.ifPresent(checkpoint -> value.set("traversal", json.readTree(checkpoint.valueJson())));
    saveRefreshes(value);
    value
        .putObject("progress")
        .put("fullGeneration", fullGeneration)
        .put("baselineStartedAt", baselineStartedAt.toString())
        .put("baselineIncomplete", baselineIncomplete)
        .put("nextIncrementalAt", nextIncrementalAt.toString())
        .put("incrementalIntervalSeconds", roots.incrementalInterval().toSeconds());
    incrementalStartedAt.ifPresent(
        t -> value.withObject("/progress").put("incrementalStartedAt", t.toString()));
    return new Checkpoint(SCHEMA_VERSION, JsJson.stringify(value));
  }

  private void saveSearches(ObjectNode incremental) {
    for (SearchPhase phase : SearchPhase.values()) {
      ObjectNode path = incremental.putObject(phase.wire());
      watermark(phase).ifPresent(w -> path.put("committedWatermark", w.toString()));
      Optional.ofNullable(explorationCompletions.get(phase))
          .ifPresent(at -> path.put("completedAt", at.toString()));
      if (searchRetries.containsKey(phase)) {
        var retries = path.putArray("retryWork");
        searchRetries
            .get(phase)
            .forEach(
                retry -> {
                  var saved =
                      retries.addObject().put("enteredGeneration", retry.enteredGeneration());
                  saved.set("work", retry.work().json());
                });
      }
      if (scans.containsKey(phase)) {
        // beginSearch 이후에만 진행 상태를 변경한다.
        Scan scan = Objects.requireNonNull(scans.get(phase));
        var progress =
            path.putObject("scan")
                .put("startedAt", scan.startedAt().toString())
                .put("lowerBound", scan.lowerBound().toString())
                .put("listingComplete", scan.listingComplete())
                .put("overlapSeconds", scan.overlap().toSeconds());
        scan.cursor().ifPresent(c -> progress.put("cursor", c));
        var work = progress.putArray("work");
        scan.work().forEach(w -> work.add(w.json()));
      }
    }
  }

  private void saveContainers(ObjectNode incremental) {
    var inventory = incremental.putArray("containers");
    for (Container container : containers.values()) {
      var saved =
          inventory
              .addObject()
              .put("id", container.id)
              .put("kind", container.kind.name())
              .put("baselineStartedAt", container.baselineStartedAt.toString());
      container.databaseId.ifPresent(id -> saved.put("databaseId", id));
      container.watermark.ifPresent(w -> saved.put("committedWatermark", w.toString()));
      container.completedAt.ifPresent(at -> saved.put("completedAt", at.toString()));
      container.watermarkPath.ifPresent(path -> saved.put("watermarkPath", path.name()));
      container.scans.forEach(
          (path, scan) ->
              saveRows(saved, path == NotionPageRefresh.Path.FULL ? "fullScan" : "scan", scan));
    }
  }

  private void saveRefreshes(ObjectNode value) {
    if (!refreshes.isEmpty() || !handoffs.isEmpty()) {
      var active = value.putArray("refreshes");
      var bodyIds = new LinkedHashSet<>(refreshes.keySet());
      bodyIds.addAll(handoffs.keySet());
      for (String id : bodyIds) {
        BodyProgress enumerating = refreshes.get(id);
        BodyProgress handoff = handoffs.get(id);
        // bodyIds는 두 맵의 키 합집합이므로 둘 중 하나가 반드시 있다.
        BodyProgress body = Objects.requireNonNull(enumerating != null ? enumerating : handoff);
        var saved = active.addObject().put("pageId", body.pageId()).put("version", body.version());
        var pending = saved.putArray("work");
        body.work().forEach(step -> pending.add(step.json()));
        var discovered = saved.putArray("discovered");
        if (handoff != null) {
          handoff.discovered().forEach(step -> discovered.add(step.json()));
          if (enumerating != null) saved.put("discoveredVersion", handoff.version());
        }
      }
    }
  }

  public CollectionCoverage coverage() {
    return coverage;
  }

  Optional<Checkpoint> fullCheckpoint() {
    return full;
  }

  void fullProgress(Checkpoint next) {
    var traversal = NotionRootTraversal.resume(json, roots, Optional.of(next));
    full = traversal.startsCycle() ? Optional.empty() : Optional.of(next);
    if (full.isEmpty() && coverage == CollectionCoverage.COMPLETE_SNAPSHOT)
      searchRetries
          .values()
          .forEach(retries -> retries.removeIf(r -> r.enteredGeneration() < fullGeneration));
  }

  Optional<NotionRootTraversal> fullTraversal() {
    return full.map(saved -> NotionRootTraversal.resume(json, roots, Optional.of(saved)));
  }

  boolean incrementalDue(Instant now) {
    return incrementalStartedAt.isPresent()
        || !now.isBefore(nextIncrementalAt)
        || !scans.isEmpty()
        || containers.values().stream()
            .filter(c -> !fullOwnsRows(c.id))
            .anyMatch(c -> c.scans.containsKey(NotionPageRefresh.Path.INCREMENTAL));
  }

  boolean deferred(NotionWork work, NotionPageRefresh.Path path) {
    return deferredWork.contains(
        path + ":" + work.operation() + ":" + NotionObjects.idKey(work.id()));
  }

  void observePage(JsonNode page, NotionPageRefresh.Path path) {
    var work = new NotionWork.Page(page.path("id").asString());
    String key = path + ":" + work.operation() + ":" + NotionObjects.idKey(work.id());
    String previous = observedVersions.put(key, page.path("last_edited_time").asString());
    if (!Objects.equals(previous, page.path("last_edited_time").asString()))
      deferredWork.remove(key);
  }

  void defer(NotionWork work, NotionPageRefresh.Path path) {
    deferredWork.add(path + ":" + work.operation() + ":" + NotionObjects.idKey(work.id()));
  }

  long scopeEpoch() {
    return scopeEpoch;
  }

  Instant beginIncremental(Instant now) {
    scopeEpoch++;
    if (incrementalStartedAt.isEmpty()) incrementalStartedAt = Optional.of(now);
    return incrementalStartedAt.orElseThrow();
  }

  void finishIncremental(Instant startedAt) {
    if (!scans.isEmpty()
        || containers.values().stream()
            .anyMatch(c -> c.scans.containsKey(NotionPageRefresh.Path.INCREMENTAL))) return;
    incrementalSucceeded(startedAt);
  }

  void incrementalSucceeded(Instant startedAt) {
    if (incrementalStartedAt.filter(startedAt::equals).isEmpty()
        || !scans.isEmpty()
        || containers.values().stream()
            .flatMap(c -> c.scans.values().stream())
            .anyMatch(scan -> scan.path() == NotionPageRefresh.Path.INCREMENTAL))
      throw new IllegalStateException("Notion incremental exploration is incomplete");
    nextIncrementalAt = startedAt.plus(roots.incrementalInterval());
    incrementalStartedAt = Optional.empty();
  }

  Scan beginSearch(SearchPhase phase, Instant now) {
    return scans.computeIfAbsent(
        phase,
        ignored ->
            new Scan(
                now,
                watermarks.getOrDefault(phase, baselineStartedAt).minus(roots.overlap()),
                roots.overlap(),
                Optional.empty(),
                false,
                searchRetries.getOrDefault(phase, List.of()).stream()
                    .map(SearchRetry::work)
                    .toList()));
  }

  void searchPage(SearchPhase phase, Optional<String> cursor, List<NotionWork> work) {
    // beginSearch가 만든 탐색을 전진시킨다.
    Scan previous = Objects.requireNonNull(scans.get(phase));
    scans.put(
        phase,
        new Scan(
            previous.startedAt(),
            previous.lowerBound(),
            previous.overlap(),
            cursor,
            cursor.isEmpty(),
            Stream.concat(previous.work().stream(), work.stream()).distinct().toList()));
  }

  void completeSearch(SearchPhase phase, Instant completedAt) {
    // 완료 판정은 시작한 탐색에만 적용한다.
    Scan scan = Objects.requireNonNull(scans.get(phase));
    if (!scan.work().isEmpty() || scan.cursor().isPresent())
      throw new IllegalStateException("Notion search still has pending work");
    watermarks.put(phase, scan.startedAt());
    scans.remove(phase);
    explorationCompletions.put(phase, completedAt);
  }

  void retrySearchWork(SearchPhase phase, NotionWork work) {
    var retries = searchRetries.computeIfAbsent(phase, ignored -> new ArrayList<>());
    if (retries.stream().noneMatch(retry -> retry.work().equals(work)))
      retries.add(new SearchRetry(work, fullGeneration));
    removeSearchWork(phase, work);
  }

  void completedSearchWork(SearchPhase phase, NotionWork completed) {
    var retries = searchRetries.get(phase);
    if (retries != null) {
      retries.removeIf(retry -> retry.work().equals(completed));
      if (retries.isEmpty()) searchRetries.remove(phase);
    }
    removeSearchWork(phase, completed);
  }

  private void removeSearchWork(SearchPhase phase, NotionWork completed) {
    // beginSearch 이후에만 진행 상태를 변경한다.
    Scan scan = Objects.requireNonNull(scans.get(phase));
    var remaining = new ArrayList<>(scan.work());
    remaining.remove(completed);
    scans.put(
        phase,
        new Scan(
            scan.startedAt(),
            scan.lowerBound(),
            scan.overlap(),
            scan.cursor(),
            scan.listingComplete(),
            remaining));
  }

  void completedSearchWork(SearchPhase phase, List<NotionWork> discovered) {
    // beginSearch 이후에만 진행 상태를 변경한다.
    Scan scan = Objects.requireNonNull(scans.get(phase));
    scans.put(
        phase,
        new Scan(
            scan.startedAt(),
            scan.lowerBound(),
            scan.overlap(),
            scan.cursor(),
            scan.listingComplete(),
            WorkQueue.of(scan.work()).afterHead(1, List.of(), discovered).items()));
  }

  void restartSearch(SearchPhase phase) {
    // beginSearch 이후에만 진행 상태를 변경한다.
    Scan scan = Objects.requireNonNull(scans.get(phase));
    scans.put(
        phase,
        new Scan(
            scan.startedAt(),
            scan.lowerBound(),
            scan.overlap(),
            Optional.empty(),
            false,
            scan.work()));
  }

  Optional<Instant> watermark(SearchPhase phase) {
    return Optional.ofNullable(watermarks.get(phase));
  }

  void rememberDatabase(String id, Instant now) {
    containers.computeIfAbsent(
        NotionMutations.Resource.DATABASE + ":" + id,
        ignored -> new Container(id, NotionMutations.Resource.DATABASE, now));
  }

  void synchronizeDatabase(String id, List<String> dataSources, Instant now) {
    rememberDatabase(id, now);
    var current = dataSources.stream().map(NotionObjects::idKey).collect(Collectors.toSet());
    containers
        .values()
        .removeIf(
            container ->
                container.databaseId.filter(parent -> NotionObjects.sameId(parent, id)).isPresent()
                    && !current.contains(NotionObjects.idKey(container.id))
                    && !fullOwnsRows(container.id));
    for (String source : dataSources) {
      rememberDataSource(source, now);
      // rememberDataSource가 방금 등록한 컨테이너다.
      Objects.requireNonNull(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + source))
              .databaseId =
          Optional.of(id);
    }
  }

  List<String> databases() {
    return containers.values().stream()
        .filter(c -> c.kind == NotionMutations.Resource.DATABASE)
        .map(c -> c.id)
        .toList();
  }

  List<String> dataSources() {
    return containers.values().stream()
        .filter(c -> c.kind == NotionMutations.Resource.DATA_SOURCE)
        .map(c -> c.id)
        .toList();
  }

  void rememberDataSource(String id, Instant now) {
    containers.computeIfAbsent(
        NotionMutations.Resource.DATA_SOURCE + ":" + id,
        ignored -> new Container(id, NotionMutations.Resource.DATA_SOURCE, now));
  }

  void retireContainer(NotionWork work) {
    if (work instanceof NotionWork.Database)
      containers
          .values()
          .removeIf(
              container ->
                  container
                          .databaseId
                          .filter(parent -> NotionObjects.sameId(parent, work.id()))
                          .isPresent()
                      && !fullOwnsRows(container.id));
    containers
        .values()
        .removeIf(
            container ->
                NotionObjects.sameId(container.id, work.id())
                    && ((work instanceof NotionWork.Database
                            && container.kind == NotionMutations.Resource.DATABASE)
                        || (work instanceof NotionWork.DataSource
                            && container.kind == NotionMutations.Resource.DATA_SOURCE)));
  }

  void retireIncrementalRows(String id) {
    var container = containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id);
    if (container != null) container.scans.remove(NotionPageRefresh.Path.INCREMENTAL);
  }

  void beginFullRows(String id) {
    fullRowOwners.add(NotionObjects.idKey(id));
  }

  void finishFullRows(String id) {
    fullRowOwners.remove(NotionObjects.idKey(id));
  }

  boolean fullOwnsRows(String id) {
    return fullRowOwners.contains(NotionObjects.idKey(id));
  }

  RowScan rows(String id, NotionPageRefresh.Path path) {
    // 등록된 컨테이너의 beginRows 이후에만 읽는다.
    return Objects.requireNonNull(
        Objects.requireNonNull(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id))
            .scans
            .get(path));
  }

  RowScan beginRows(String id, NotionPageRefresh.Path path, Instant now) {
    // rememberDataSource 이후에만 행 탐색을 진행한다.
    Container container =
        Objects.requireNonNull(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id));
    return container.scans.computeIfAbsent(
        path,
        ignored ->
            new RowScan(
                now,
                path,
                path == NotionPageRefresh.Path.INCREMENTAL
                    ? container.watermark.map(w -> w.minus(roots.overlap()))
                    : Optional.empty(),
                roots.overlap(),
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                List.of()));
  }

  RowScan reopenRows(String id, NotionPageRefresh.Path path, Instant now) {
    // rememberDataSource 이후에만 행 탐색을 진행한다.
    Container container =
        Objects.requireNonNull(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id));
    // beginRows가 해당 경로의 진행 상태를 만든다.
    RowScan scan = Objects.requireNonNull(container.scans.get(path));
    var reopened =
        new RowScan(
            now,
            path,
            scan.lowerBound(),
            scan.overlap(),
            Optional.empty(),
            Optional.empty(),
            false,
            false,
            Optional.empty(),
            scan.work());
    container.scans.put(path, reopened);
    return reopened;
  }

  void rowsPage(
      String id,
      NotionPageRefresh.Path path,
      Optional<String> cursor,
      Optional<Instant> lastCreated,
      boolean limited,
      List<NotionWork> work) {
    // rememberDataSource 이후에만 행 탐색을 진행한다.
    Container container =
        Objects.requireNonNull(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id));
    // beginRows가 해당 경로의 진행 상태를 만든다.
    RowScan scan = Objects.requireNonNull(container.scans.get(path));
    container.scans.put(
        path,
        new RowScan(
            scan.startedAt(),
            path,
            scan.lowerBound(),
            scan.overlap(),
            scan.createdFrom(),
            lastCreated.or(scan::lastCreated),
            scan.limited() || limited,
            cursor.isEmpty(),
            cursor,
            Stream.concat(scan.work().stream(), work.stream()).distinct().toList()));
  }

  void completedRowWork(String id, NotionPageRefresh.Path path, NotionWork completed) {
    // rememberDataSource 이후에만 행 탐색을 진행한다.
    Container container =
        Objects.requireNonNull(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id));
    // beginRows가 해당 경로의 진행 상태를 만든다.
    RowScan scan = Objects.requireNonNull(container.scans.get(path));
    var remaining = new ArrayList<>(scan.work());
    remaining.remove(completed);
    container.scans.put(
        path,
        new RowScan(
            scan.startedAt(),
            path,
            scan.lowerBound(),
            scan.overlap(),
            scan.createdFrom(),
            scan.lastCreated(),
            scan.limited(),
            scan.listingComplete(),
            scan.cursor(),
            remaining));
  }

  void restartRows(String id, NotionPageRefresh.Path path) {
    // rememberDataSource 이후에만 행 탐색을 진행한다.
    Container container =
        Objects.requireNonNull(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id));
    // beginRows가 해당 경로의 진행 상태를 만든다.
    RowScan scan = Objects.requireNonNull(container.scans.get(path));
    container.scans.put(
        path,
        new RowScan(
            scan.startedAt(),
            path,
            scan.lowerBound(),
            scan.overlap(),
            Optional.empty(),
            Optional.empty(),
            false,
            false,
            Optional.empty(),
            scan.work()));
  }

  void nextRowsWindow(String id, NotionPageRefresh.Path path) {
    // rememberDataSource 이후에만 행 탐색을 진행한다.
    Container container =
        Objects.requireNonNull(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id));
    // beginRows가 해당 경로의 진행 상태를 만든다.
    RowScan scan = Objects.requireNonNull(container.scans.get(path));
    if (scan.cursor().isPresent()) throw new IllegalStateException("Rows still pending");
    Instant boundary =
        scan.lastCreated()
            .orElseThrow(
                () -> new NotionProtocolException("Notion creation window cannot advance"));
    if (scan.createdFrom().filter(from -> !boundary.isAfter(from)).isPresent())
      throw new NotionProtocolException("Notion creation window cannot advance");
    container.scans.put(
        path,
        new RowScan(
            scan.startedAt(),
            path,
            scan.lowerBound(),
            scan.overlap(),
            Optional.of(boundary),
            Optional.empty(),
            false,
            false,
            Optional.empty(),
            scan.work()));
  }

  void completeRows(String id, NotionPageRefresh.Path path, Instant completedAt) {
    // rememberDataSource 이후에만 행 탐색을 진행한다.
    Container container =
        Objects.requireNonNull(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id));
    // beginRows가 해당 경로의 진행 상태를 만든다.
    RowScan scan = Objects.requireNonNull(container.scans.get(path));
    if (!scan.work().isEmpty() || scan.cursor().isPresent() || scan.limited())
      throw new IllegalStateException("Rows still pending");
    if (path == NotionPageRefresh.Path.INCREMENTAL
        || (container.watermark.isEmpty()
            && !container.scans.containsKey(NotionPageRefresh.Path.INCREMENTAL))) {
      container.watermark = Optional.of(scan.startedAt());
      container.completedAt = Optional.of(completedAt);
      container.watermarkPath = Optional.of(path);
    }
    container.scans.remove(path);
  }

  Optional<Instant> rowWatermark(String id) {
    return Optional.ofNullable(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id))
        .flatMap(c -> c.watermark);
  }

  private Optional<Instant> successfulRowExploration(String id) {
    return Optional.ofNullable(containers.get(NotionMutations.Resource.DATA_SOURCE + ":" + id))
        .filter(
            container ->
                container
                    .watermarkPath
                    .filter(path -> path == NotionPageRefresh.Path.INCREMENTAL)
                    .isPresent())
        .flatMap(container -> container.watermark);
  }

  private static RowScan readRows(JsonNode scan) {
    fields(
        scan,
        Set.of("startedAt", "path", "limited", "work"),
        Set.of(
            "lowerBound",
            "createdFrom",
            "lastCreated",
            "cursor",
            "listingComplete",
            "overlapSeconds"));
    if ((scan.has("listingComplete") && !scan.path("listingComplete").isBoolean())
        || !scan.path("limited").isBoolean()
        || !scan.path("work").isArray()
        || (scan.has("cursor") && !NotionObjects.nonEmptyString(scan.get("cursor"))))
      throw NotionProtocolException.invalidCheckpoint();
    var work = new ArrayList<NotionWork>();
    scan.path("work").forEach(w -> work.add(NotionWork.read(w)));
    try {
      return new RowScan(
          Instant.parse(scan.path("startedAt").asString()),
          NotionPageRefresh.Path.valueOf(scan.path("path").asString()),
          savedInstant(scan, "lowerBound"),
          savedOverlap(scan),
          savedInstant(scan, "createdFrom"),
          savedInstant(scan, "lastCreated"),
          scan.path("limited").asBoolean(),
          scan.path("listingComplete").asBoolean(),
          scan.has("cursor") ? Optional.of(scan.path("cursor").asString()) : Optional.empty(),
          work);
    } catch (IllegalArgumentException invalid) {
      throw NotionProtocolException.invalidCheckpoint();
    }
  }

  private void saveRows(ObjectNode saved, String field, RowScan scan) {
    var pending =
        saved
            .putObject(field)
            .put("startedAt", scan.startedAt().toString())
            .put("path", scan.path().name())
            .put("limited", scan.limited())
            .put("listingComplete", scan.listingComplete());
    scan.lowerBound()
        .ifPresent(
            t ->
                pending
                    .put("lowerBound", t.toString())
                    .put("overlapSeconds", scan.overlap().toSeconds()));
    scan.createdFrom().ifPresent(t -> pending.put("createdFrom", t.toString()));
    scan.lastCreated().ifPresent(t -> pending.put("lastCreated", t.toString()));
    scan.cursor().ifPresent(c -> pending.put("cursor", c));
    var work = pending.putArray("work");
    scan.work().forEach(w -> work.add(w.json()));
  }

  private static long readGeneration(JsonNode saved, String key) {
    if (!saved.has(key)) return 0;
    var generation = saved.get(key);
    if (!generation.isIntegralNumber()
        || !generation.canConvertToLong()
        || generation.asLong() < 0
        || generation.asLong() == Long.MAX_VALUE) throw NotionProtocolException.invalidCheckpoint();
    return generation.asLong();
  }

  private static Duration savedInterval(JsonNode progress) {
    return savedDuration(
        progress, "incrementalIntervalSeconds", Roots.DEFAULT_INCREMENTAL_INTERVAL);
  }

  private static Duration savedOverlap(JsonNode scan) {
    return savedDuration(scan, "overlapSeconds", Roots.DEFAULT_OVERLAP);
  }

  private static Duration savedDuration(JsonNode value, String key, Duration fallback) {
    if (!value.has(key)) return fallback;
    var seconds = value.get(key);
    if (!seconds.isIntegralNumber() || !seconds.canConvertToInt() || seconds.asInt() <= 0)
      throw NotionProtocolException.invalidCheckpoint();
    return Duration.ofSeconds(seconds.asInt());
  }

  private static Optional<Instant> savedInstant(JsonNode saved, String field) {
    return saved.has(field)
        ? Optional.of(Instant.parse(saved.path(field).asString()))
        : Optional.empty();
  }

  private static void validateEnvelope(JsonNode value) {
    fields(value, Set.of("scope", "incremental", "progress"), Set.of("traversal", "refreshes"));
    validateRefreshes(value);
    validateScope(value.path("scope"));
    validateSearches(value.path("incremental"));
    JsonNode progress = value.path("progress");
    fields(
        progress,
        Set.of("baselineStartedAt", "baselineIncomplete", "nextIncrementalAt"),
        Set.of("incrementalStartedAt", "incrementalIntervalSeconds", "fullGeneration"));
    if (!progress.path("baselineIncomplete").isBoolean()
        || !progress.path("baselineStartedAt").isString()
        || !progress.path("nextIncrementalAt").isString())
      throw NotionProtocolException.invalidCheckpoint();
    Instant.parse(progress.path("nextIncrementalAt").asString());
  }

  private static void validateRefreshes(JsonNode value) {
    if (value.has("refreshes")) {
      if (!value.get("refreshes").isArray()) throw NotionProtocolException.invalidCheckpoint();
      var bodyIds = new HashSet<String>();
      for (JsonNode body : value.get("refreshes")) {
        fields(
            body, Set.of("pageId", "version", "work", "discovered"), Set.of("discoveredVersion"));
        if (!NotionObjects.nonEmptyString(body.get("pageId"))
            || !NotionObjects.nonEmptyString(body.get("version"))
            || !bodyIds.add(NotionObjects.idKey(body.path("pageId").asString()))
            || !body.path("work").isArray()
            || !body.path("discovered").isArray())
          throw NotionProtocolException.invalidCheckpoint();
        if (body.has("discoveredVersion")
            && (!NotionObjects.nonEmptyString(body.get("discoveredVersion"))
                || body.path("discovered").isEmpty()))
          throw NotionProtocolException.invalidCheckpoint();
        for (JsonNode step : body.path("work"))
          if (!(NotionWork.read(step) instanceof NotionWork.BlockChildren))
            throw NotionProtocolException.invalidCheckpoint();
        body.path("discovered").forEach(NotionWork::read);
      }
    }
  }

  private static void validateScope(JsonNode scope) {
    fields(scope, Set.of("rootPageIds", "discoverSharedPages", "configFingerprint"), Set.of());
    if (!scope.path("rootPageIds").isArray()
        || scope.path("rootPageIds").isEmpty()
        || !scope.path("discoverSharedPages").isBoolean()
        || !NotionObjects.nonEmptyString(scope.get("configFingerprint")))
      throw NotionProtocolException.invalidCheckpoint();
    var rootIds = new HashSet<String>();
    for (JsonNode root : scope.path("rootPageIds"))
      if (!NotionObjects.nonEmptyString(root) || !rootIds.add(root.asString()))
        throw NotionProtocolException.invalidCheckpoint();
  }

  private static void validateSearches(JsonNode incremental) {
    fields(incremental, Set.of("active", "trash", "containers"), Set.of());
    if (!incremental.path("containers").isArray())
      throw NotionProtocolException.invalidCheckpoint();
    for (SearchPhase phase : SearchPhase.values()) {
      JsonNode path = incremental.path(phase.wire());
      fields(
          path,
          Set.of(),
          Set.of("committedWatermark", "completedAt", "scan", "fullScan", "retryWork"));
      if (path.has("committedWatermark") && !path.get("committedWatermark").isString())
        throw NotionProtocolException.invalidCheckpoint();
      if (path.has("retryWork") && !path.path("retryWork").isArray())
        throw NotionProtocolException.invalidCheckpoint();
      if (path.has("scan")) {
        JsonNode scan = path.get("scan");
        fields(
            scan,
            Set.of("startedAt", "lowerBound", "work"),
            Set.of("cursor", "listingComplete", "overlapSeconds"));
        if (scan.has("listingComplete") && !scan.path("listingComplete").isBoolean())
          throw NotionProtocolException.invalidCheckpoint();
        if (!scan.path("work").isArray()
            || !scan.path("startedAt").isString()
            || !scan.path("lowerBound").isString()
            || (scan.has("cursor") && !NotionObjects.nonEmptyString(scan.get("cursor"))))
          throw NotionProtocolException.invalidCheckpoint();
      }
    }
  }

  private static void fields(JsonNode value, Set<String> required, Set<String> optional) {
    if (!value.isObject()) throw NotionProtocolException.invalidCheckpoint();
    var keys = new HashSet<>(value.propertyNames());
    if (!keys.containsAll(required)) throw NotionProtocolException.invalidCheckpoint();
    keys.removeAll(required);
    keys.removeAll(optional);
    if (!keys.isEmpty()) throw NotionProtocolException.invalidCheckpoint();
  }
}
