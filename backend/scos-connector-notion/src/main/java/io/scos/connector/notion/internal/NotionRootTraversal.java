package io.scos.connector.notion.internal;

import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.Checkpoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * roots 모드의 영속 작업 큐와 사이클 경계(TS NotionRootTraversal, 체크포인트 v3 — F12 저장 호환). 사이클은 설정한 루트 페이지들의 조회로
 * 시작한다(초기 시드). 작업 하나를 끝내면 그 이어지는 페이지(continuation)를 맨 앞에, 새로 찾은 작업을 맨 뒤에 둔다. 큐가 비면 사이클이 끝나고 초기 시드로
 * 돌아간다. discoverSharedPages면 그 전에 검색 단계(active → trash)를 한 번 더 걷는다(discovery). v1·v2 체크포인트(검색 모드
 * 시절)는 초기 시드로 읽는다. 저장값의 키와 순서는 TS rootCheckpoint와 같다.
 */
public final class NotionRootTraversal {
  static final int SCHEMA_VERSION = 3;

  private static final Set<String> KEYS =
      Set.of(
          "mode",
          "rootPageIds",
          "discoverSharedPages",
          "stage",
          "pendingWork",
          "searchPhase",
          "searchCursor");

  enum Stage {
    TRAVERSAL,
    DISCOVERY;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** 다음에 할 일: 큐의 첫 작업, 또는 (큐가 빈 discovery 단계면) 검색 한 페이지. */
  sealed interface Step {
    record Work(NotionWork work) implements Step {}

    record Search(SearchPhase phase, Optional<String> cursor) implements Step {}
  }

  /** 페이지 하나를 낸 뒤의 체크포인트와, 그 페이지로 사이클이 끝났는지. */
  record Advance(Checkpoint checkpoint, boolean cycleComplete) {}

  private record State(
      Stage stage,
      List<NotionWork> pending,
      Optional<SearchPhase> searchPhase,
      Optional<String> searchCursor) {}

  private final Roots config;
  private State state;

  private NotionRootTraversal(Roots config, State state) {
    this.config = config;
    this.state = state;
  }

  /** 저장된 체크포인트에서 이어 간다. 형식이 틀리거나 설정한 루트와 다르면 {@link NotionProtocolException}(TS 메시지). */
  public static NotionRootTraversal resume(
      ObjectMapper json, Roots config, Optional<Checkpoint> stored) {
    return new NotionRootTraversal(config, read(json, config, stored));
  }

  /**
   * 이 체크포인트에서 시작하는 run이 새 사이클 전체를 보는가(TS isInitialRootCheckpoint): 체크포인트가 없거나, v1·v2이거나, 초기 시드와 같으면
   * 참. 사이클 중간에서 재개하는 run은 앞 run이 이미 본 것을 보지 않으므로 complete_snapshot이라고 보고하면 안 된다.
   */
  public static boolean startsCycle(ObjectMapper json, Roots config, Optional<Checkpoint> stored) {
    State s = read(json, config, stored);
    if (s.stage() != Stage.TRAVERSAL
        || s.searchPhase().isPresent()
        || s.searchCursor().isPresent()
        || s.pending().size() != config.rootPageIds().size()) return false;
    for (int i = 0; i < s.pending().size(); i++)
      if (!(s.pending().get(i) instanceof NotionWork.Page page)
          || !page.pageId().equals(config.rootPageIds().get(i))) return false;
    return true;
  }

  Step next() {
    if (!state.pending().isEmpty()) return new Step.Work(state.pending().getFirst());
    return new Step.Search(state.searchPhase().orElseThrow(), state.searchCursor());
  }

  /** 큐의 첫 작업을 끝냈다(TS completeWork). */
  Advance completeWork(Optional<NotionWork> continuation, List<NotionWork> discovered) {
    List<NotionWork> pending = new ArrayList<>();
    continuation.ifPresent(pending::add);
    pending.addAll(state.pending().subList(1, state.pending().size()));
    pending.addAll(discovered);
    if (!pending.isEmpty() || state.stage() == Stage.DISCOVERY) {
      state = new State(state.stage(), pending, state.searchPhase(), state.searchCursor());
      return advance(false);
    }
    if (config.discoverSharedPages()) {
      state =
          new State(Stage.DISCOVERY, List.of(), Optional.of(SearchPhase.ACTIVE), Optional.empty());
      return advance(false);
    }
    state = initial(config);
    return advance(true);
  }

  /** 검색 한 페이지를 끝냈다(TS completeSearch): active 단계의 페이지는 본문 블록 작업이 된다. */
  Advance completeSearch(List<String> pageIds, Optional<String> nextCursor) {
    SearchPhase phase = state.searchPhase().orElseThrow();
    List<NotionWork> pending =
        phase == SearchPhase.ACTIVE
            ? pageIds.stream()
                .<NotionWork>map(id -> new NotionWork.BlockChildren(id, Optional.empty()))
                .toList()
            : List.of();
    if (nextCursor.isPresent()) {
      state = new State(state.stage(), pending, state.searchPhase(), nextCursor);
      return advance(false);
    }
    if (phase == SearchPhase.ACTIVE) {
      state = new State(Stage.DISCOVERY, pending, Optional.of(SearchPhase.TRASH), Optional.empty());
      return advance(false);
    }
    state = initial(config);
    return advance(true);
  }

  private Advance advance(boolean cycleComplete) {
    return new Advance(checkpoint(config, state), cycleComplete);
  }

  private static State initial(Roots config) {
    return new State(
        Stage.TRAVERSAL,
        config.rootPageIds().stream().<NotionWork>map(NotionWork.Page::new).toList(),
        Optional.empty(),
        Optional.empty());
  }

  /**
   * TS rootCheckpoint: 키 순서 mode·rootPageIds·discoverSharedPages(참일
   * 때만)·stage·pendingWork·searchPhase·searchCursor.
   */
  private static Checkpoint checkpoint(Roots config, State state) {
    ObjectNode value = JsonNodeFactory.instance.objectNode().put("mode", "roots");
    ArrayNode roots = value.putArray("rootPageIds");
    config.rootPageIds().forEach(roots::add);
    if (config.discoverSharedPages()) value.put("discoverSharedPages", true);
    value.put("stage", state.stage().wire());
    ArrayNode pending = value.putArray("pendingWork");
    state.pending().forEach(work -> pending.add(work.json()));
    state.searchPhase().ifPresent(phase -> value.put("searchPhase", phase.wire()));
    state.searchCursor().ifPresent(cursor -> value.put("searchCursor", cursor));
    return new Checkpoint(SCHEMA_VERSION, JsJson.stringify(value));
  }

  /** TS readRootCheckpoint: 형식 → 루트 일치 → 작업 → 단계별 조건 순으로 본다. */
  private static State read(ObjectMapper json, Roots config, Optional<Checkpoint> stored) {
    if (stored.isEmpty() || stored.get().schemaVersion() < SCHEMA_VERSION) return initial(config);
    JsonNode value = parse(json, stored.get());
    if (stored.get().schemaVersion() != SCHEMA_VERSION
        || !value.isObject()
        || !text(value.get("mode"), "roots")
        || !stringArray(value.get("rootPageIds"))
        || (value.has("discoverSharedPages") && !value.get("discoverSharedPages").isBoolean())
        || stage(value.get("stage")).isEmpty()
        || value.get("pendingWork") == null
        || !value.get("pendingWork").isArray()
        || (value.has("searchPhase") && phase(value.get("searchPhase")).isEmpty())
        || (value.has("searchCursor") && !NotionObjects.nonEmptyString(value.get("searchCursor")))
        || !KEYS.containsAll(new HashSet<>(value.propertyNames())))
      throw NotionProtocolException.invalidCheckpoint();
    List<String> roots = new ArrayList<>();
    value.get("rootPageIds").forEach(id -> roots.add(id.asString()));
    boolean discover =
        value.has("discoverSharedPages") && value.get("discoverSharedPages").asBoolean();
    if (!roots.equals(config.rootPageIds()) || discover != config.discoverSharedPages())
      throw new NotionProtocolException(NotionProtocolException.ROOTS_CHANGED);
    List<NotionWork> pending = new ArrayList<>();
    value.get("pendingWork").forEach(work -> pending.add(NotionWork.read(work)));
    Stage stage = stage(value.get("stage")).orElseThrow();
    Optional<SearchPhase> searchPhase =
        value.has("searchPhase") ? phase(value.get("searchPhase")) : Optional.empty();
    Optional<String> searchCursor =
        value.has("searchCursor")
            ? Optional.of(value.get("searchCursor").asString())
            : Optional.empty();
    if ((stage == Stage.TRAVERSAL
            && (pending.isEmpty() || value.has("searchPhase") || value.has("searchCursor")))
        || (stage == Stage.DISCOVERY && (!config.discoverSharedPages() || searchPhase.isEmpty())))
      throw NotionProtocolException.invalidCheckpoint();
    return new State(stage, List.copyOf(pending), searchPhase, searchCursor);
  }

  private static JsonNode parse(ObjectMapper json, Checkpoint stored) {
    try {
      return json.readTree(stored.valueJson());
    } catch (JacksonException e) {
      throw NotionProtocolException.invalidCheckpoint();
    }
  }

  private static boolean stringArray(JsonNode value) {
    if (value == null || !value.isArray()) return false;
    for (JsonNode item : value) if (!NotionObjects.nonEmptyString(item)) return false;
    return true;
  }

  private static Optional<Stage> stage(JsonNode value) {
    for (Stage stage : Stage.values()) if (text(value, stage.wire())) return Optional.of(stage);
    return Optional.empty();
  }

  private static Optional<SearchPhase> phase(JsonNode value) {
    return value != null && value.isString()
        ? SearchPhase.fromWire(value.asString())
        : Optional.empty();
  }

  private static boolean text(JsonNode value, String expected) {
    return value != null && value.isString() && value.asString().equals(expected);
  }
}
