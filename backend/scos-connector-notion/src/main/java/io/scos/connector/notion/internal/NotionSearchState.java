package io.scos.connector.notion.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.Checkpoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 검색 모드의 재개 위치(TS NotionCheckpointValue, F12 저장 호환): 휴지통 단계, 다음 검색 커서, 본문을 아직 읽지 않은 블록 부모 대기열. 쓰는
 * 형식은 v2 `{phase, searchCursor?, pendingBlockParents?}`이고(키 순서도 TS와 같다), 읽기는 v1 `{phase,
 * cursor?}`(블록 대기열 이전)도 받는다. 휴지통 단계에는 블록 대기열이 없다.
 */
public record NotionSearchState(
    SearchPhase phase, Optional<String> searchCursor, List<BlockParent> pending) {
  static final int SCHEMA_VERSION = 2;
  private static final int LEGACY_VERSION = 1;

  /** 본문(자식 블록)을 읽을 블록·페이지와 그 다음 페이지 커서. */
  record BlockParent(String blockId, Optional<String> cursor) {}

  public NotionSearchState {
    pending = List.copyOf(pending);
  }

  static NotionSearchState start() {
    return new NotionSearchState(SearchPhase.ACTIVE, Optional.empty(), List.of());
  }

  /** TS readCheckpoint: 없으면 active 처음부터, v1, v2. 그 밖은 {@link NotionProtocolException}. */
  public static NotionSearchState read(ObjectMapper json, Optional<Checkpoint> stored) {
    if (stored.isEmpty()) return start();
    JsonNode value;
    try {
      value = json.readTree(stored.get().valueJson());
    } catch (JacksonException e) {
      throw NotionProtocolException.invalidCheckpoint();
    }
    if (stored.get().schemaVersion() == LEGACY_VERSION) return readLegacy(value);
    if (stored.get().schemaVersion() != SCHEMA_VERSION
        || !value.isObject()
        || phase(value).isEmpty()
        || !only(value, Set.of("phase", "searchCursor", "pendingBlockParents"))
        || (value.has("searchCursor") && !NotionObjects.nonEmptyString(value.get("searchCursor")))
        || (value.has("pendingBlockParents") && !value.get("pendingBlockParents").isArray()))
      throw NotionProtocolException.invalidCheckpoint();
    List<BlockParent> pending = new ArrayList<>();
    if (value.has("pendingBlockParents"))
      value.get("pendingBlockParents").forEach(parent -> pending.add(blockParent(parent)));
    SearchPhase phase = phase(value).orElseThrow();
    if (phase == SearchPhase.TRASH && !pending.isEmpty())
      throw NotionProtocolException.invalidCheckpoint();
    return new NotionSearchState(phase, text(value, "searchCursor"), pending);
  }

  private static NotionSearchState readLegacy(JsonNode value) {
    if (!value.isObject()
        || phase(value).isEmpty()
        || !only(value, Set.of("phase", "cursor"))
        || (value.has("cursor") && !NotionObjects.nonEmptyString(value.get("cursor"))))
      throw NotionProtocolException.invalidCheckpoint();
    return new NotionSearchState(phase(value).orElseThrow(), text(value, "cursor"), List.of());
  }

  private static BlockParent blockParent(JsonNode value) {
    if (!value.isObject()
        || !NotionObjects.nonEmptyString(value.get("blockId"))
        || !only(value, Set.of("blockId", "cursor"))
        || (value.has("cursor") && !NotionObjects.nonEmptyString(value.get("cursor"))))
      throw NotionProtocolException.invalidCheckpoint();
    return new BlockParent(value.get("blockId").asString(), text(value, "cursor"));
  }

  /**
   * 검색 한 페이지 뒤(TS nextSearchState): active 단계의 페이지는 본문 대기열이 되고, 이 단계를 다 봤으면 다음 단계(trash ↔ active)로.
   */
  NotionSearchState afterSearch(Optional<String> nextCursor, List<String> pageIds) {
    List<BlockParent> parents =
        phase == SearchPhase.ACTIVE
            ? pageIds.stream().map(id -> new BlockParent(id, Optional.empty())).toList()
            : List.of();
    if (!parents.isEmpty() || nextCursor.isPresent())
      return new NotionSearchState(phase, nextCursor, parents);
    return new NotionSearchState(
        phase == SearchPhase.ACTIVE ? SearchPhase.TRASH : SearchPhase.ACTIVE,
        Optional.empty(),
        List.of());
  }

  /** 블록 부모 하나를 처리한 뒤(TS nextBlockState): 대기열과 검색 커서가 모두 비면 휴지통 단계로. */
  NotionSearchState afterBlocks(List<BlockParent> nextPending) {
    if (!nextPending.isEmpty() || searchCursor.isPresent())
      return new NotionSearchState(phase, searchCursor, nextPending);
    return new NotionSearchState(SearchPhase.TRASH, Optional.empty(), List.of());
  }

  /** TS notionCheckpoint: v2, 키 순서 phase·searchCursor·pendingBlockParents(비어 있지 않을 때만). */
  Checkpoint checkpoint() {
    ObjectNode value = JsonNodeFactory.instance.objectNode().put("phase", phase.wire());
    searchCursor.ifPresent(cursor -> value.put("searchCursor", cursor));
    if (!pending.isEmpty()) {
      ArrayNode parents = value.putArray("pendingBlockParents");
      for (BlockParent parent : pending) {
        ObjectNode p = parents.addObject().put("blockId", parent.blockId());
        parent.cursor().ifPresent(cursor -> p.put("cursor", cursor));
      }
    }
    return new Checkpoint(SCHEMA_VERSION, JsJson.stringify(value));
  }

  private static Optional<SearchPhase> phase(JsonNode value) {
    JsonNode phase = value.get("phase");
    return phase != null && phase.isString()
        ? SearchPhase.fromWire(phase.asString())
        : Optional.empty();
  }

  private static Optional<String> text(JsonNode value, String key) {
    return value.has(key) ? Optional.of(value.get(key).asString()) : Optional.empty();
  }

  private static boolean only(JsonNode value, Set<String> allowed) {
    return allowed.containsAll(new HashSet<>(value.propertyNames()));
  }
}
