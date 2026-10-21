package io.scos.connector.notion.internal;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * roots 모드 작업 큐의 작업 하나(TS NotionWorkItem, 체크포인트 v3 `pendingWork` 항목 — F12 저장 호환). JSON 키와 순서는 TS
 * serializeWorkItem과 같다. 휴지통 단계 data source 행 작업은 더 만들지 않지만(이 API가 in_trash 필터를 거부한다) 옛 체크포인트에 남은
 * 것을 읽을 수 있어야 한다.
 */
sealed interface NotionWork {
  /** 체크포인트·거부 위치의 작업 종류 이름(TS NotionWorkItem.kind). */
  String kind();

  /** 대상 id의 키 이름(pageId·blockId·databaseId·dataSourceId). */
  String idKey();

  String id();

  /** 이 작업의 공급자 호출 이름(지표·거부 위치의 operation). */
  String operation();

  /** 체크포인트 v3의 작업 하나: kind·id 키 뒤에 종류별 필드(TS serializeWorkItem 키 순서). */
  default ObjectNode json() {
    ObjectNode out = JsonNodeFactory.instance.objectNode().put("kind", kind()).put(idKey(), id());
    extras(out);
    return out;
  }

  /** 거부 위치(TS providerLocator, operation 앞까지): workKind와 대상 id. */
  default ObjectNode locator() {
    return JsonNodeFactory.instance.objectNode().put("workKind", kind()).put(idKey(), id());
  }

  /** json()에서 kind·id 뒤에 붙는 종류별 필드. */
  default void extras(ObjectNode out) {}

  /** 페이지 조회 — 설정한 루트이거나 child_page 블록이 가리키는 페이지. */
  record Page(String pageId) implements NotionWork {
    static final String KIND = "page";

    @Override
    public String kind() {
      return KIND;
    }

    @Override
    public String idKey() {
      return "pageId";
    }

    @Override
    public String id() {
      return pageId;
    }

    @Override
    public String operation() {
      return "notion.pages.retrieve";
    }
  }

  /** 블록 자식 한 페이지. */
  record BlockChildren(String blockId, Optional<String> cursor) implements NotionWork {
    static final String KIND = "block_children";

    @Override
    public String kind() {
      return KIND;
    }

    @Override
    public String idKey() {
      return "blockId";
    }

    @Override
    public String id() {
      return blockId;
    }

    @Override
    public String operation() {
      return "notion.blocks.children.list";
    }

    @Override
    public void extras(ObjectNode out) {
      cursor.ifPresent(c -> out.put("cursor", c));
    }
  }

  /** child_database 블록이 가리키는 database. */
  record Database(String databaseId) implements NotionWork {
    static final String KIND = "database";

    @Override
    public String kind() {
      return KIND;
    }

    @Override
    public String idKey() {
      return "databaseId";
    }

    @Override
    public String id() {
      return databaseId;
    }

    @Override
    public String operation() {
      return "notion.databases.retrieve";
    }
  }

  record DataSource(String dataSourceId) implements NotionWork {
    static final String KIND = "data_source";

    @Override
    public String kind() {
      return KIND;
    }

    @Override
    public String idKey() {
      return "dataSourceId";
    }

    @Override
    public String id() {
      return dataSourceId;
    }

    @Override
    public String operation() {
      return "notion.data_sources.retrieve";
    }
  }

  /** data source 행 한 페이지. */
  record DataSourceRows(String dataSourceId, SearchPhase phase, Optional<String> cursor)
      implements NotionWork {
    static final String KIND = "data_source_rows";

    @Override
    public String kind() {
      return KIND;
    }

    @Override
    public String idKey() {
      return "dataSourceId";
    }

    @Override
    public String id() {
      return dataSourceId;
    }

    @Override
    public String operation() {
      return "notion.data_sources.query";
    }

    @Override
    public void extras(ObjectNode out) {
      out.put("phase", phase.wire());
      cursor.ifPresent(c -> out.put("cursor", c));
    }
  }

  /** 체크포인트의 작업 하나(TS readWorkItem). 종류별 키만 있어야 하고 id는 빈 값이 아닌 문자열, cursor는 없거나 빈 값이 아닌 문자열이다. */
  static NotionWork read(JsonNode value) {
    if (value == null || !value.isObject() || !NotionObjects.nonEmptyString(value.get("kind")))
      throw NotionProtocolException.invalidCheckpoint();
    return switch (value.get("kind").asString()) {
      case Page.KIND -> new Page(id(value, "pageId", Set.of()));
      case BlockChildren.KIND ->
          new BlockChildren(id(value, "blockId", Set.of("cursor")), cursor(value));
      case Database.KIND -> new Database(id(value, "databaseId", Set.of()));
      case DataSource.KIND -> new DataSource(id(value, "dataSourceId", Set.of()));
      case DataSourceRows.KIND -> {
        String id = id(value, "dataSourceId", Set.of("phase", "cursor"));
        JsonNode phase = value.get("phase");
        if (phase == null || !phase.isString()) throw NotionProtocolException.invalidCheckpoint();
        yield new DataSourceRows(
            id,
            SearchPhase.fromWire(phase.asString())
                .orElseThrow(NotionProtocolException::invalidCheckpoint),
            cursor(value));
      }
      default -> throw NotionProtocolException.invalidCheckpoint();
    };
  }

  /** 종류별 id 키를 읽는다. kind·id 키와 optional 밖의 키가 있으면 거부한다. */
  private static String id(JsonNode value, String idKey, Set<String> optional) {
    Set<String> extra = new HashSet<>(value.propertyNames());
    extra.remove("kind");
    extra.remove(idKey);
    extra.removeAll(optional);
    if (!extra.isEmpty() || !NotionObjects.nonEmptyString(value.get(idKey)))
      throw NotionProtocolException.invalidCheckpoint();
    return value.get(idKey).asString();
  }

  private static Optional<String> cursor(JsonNode value) {
    if (!value.has("cursor")) return Optional.empty();
    if (!NotionObjects.nonEmptyString(value.get("cursor")))
      throw NotionProtocolException.invalidCheckpoint();
    return Optional.of(value.get("cursor").asString());
  }
}
