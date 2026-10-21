package io.scos.connector.notion.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Notion 객체 → 원본 변경(TS toMutation·toBlockMutation·toContainerMutation). 휴지통(in_trash)이면
 * delete(sourceVersion `trashed:<last_edited_time>`, 일어난 시각 = 편집 시각), 아니면 원문 JSON 캡처(TS
 * `JSON.stringify` 바이트 — {@link JsJson})를 담은 upsert(sourceVersion = last_edited_time 원문). 순서는 종류별
 * scheme과 정규화한 편집 시각(TS `new Date(v).toISOString()`)이다.
 */
public final class NotionMutations {
  static final String BLOCK_PREFIX = "block:";
  static final String INVALID_TIMESTAMP = "Invalid Notion page timestamp";
  static final String WRONG_TRASH_PHASE =
      "Notion search returned a page from the wrong trash phase";

  private static final String JSON_MEDIA = "application/json";
  private static final String TRASHED = "trashed:";

  /** TS Date#toISOString: 밀리초 세 자리, UTC `Z`. */
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** 원본 종류(resourceType·externalId 접두·순서 scheme). */
  enum Resource {
    PAGE("notion.page", "", "notion.page-state.v1"),
    BLOCK("notion.block", BLOCK_PREFIX, "notion.block-state.v1"),
    DATABASE("notion.database", "database:", "notion.database-state.v1"),
    DATA_SOURCE("notion.data_source", "data-source:", "notion.data-source-state.v1");

    final String resourceType;
    final String prefix;
    final String scheme;

    Resource(String resourceType, String prefix, String scheme) {
      this.resourceType = resourceType;
      this.prefix = prefix;
      this.scheme = scheme;
    }
  }

  private final ContentCapture capture;
  private final Clock clock;

  public NotionMutations(ContentCapture capture, Clock clock) {
    this.capture = capture;
    this.clock = clock;
  }

  /** 페이지: metadata는 url·public_url(문자열일 때만, TS 키 url·publicUrl). */
  RawMutation page(JsonNode page) {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    if (page.has("url") && page.get("url").isString())
      metadata.put("url", page.get("url").asString());
    if (page.has("public_url") && page.get("public_url").isString())
      metadata.put("publicUrl", page.get("public_url").asString());
    return mutation(Resource.PAGE, page, metadata);
  }

  /** 검색 결과 페이지들: 검색한 휴지통 단계와 다른 페이지가 오면 Error(TS — 페이지마다 확인하고 원본을 만든다). */
  List<RawMutation> searchResults(List<JsonNode> pages, SearchPhase phase) {
    List<RawMutation> out = new ArrayList<>();
    for (JsonNode page : pages) {
      if (page.get("in_trash").asBoolean() != (phase == SearchPhase.TRASH))
        throw new NotionProtocolException(WRONG_TRASH_PHASE);
      out.add(page(page));
    }
    return out;
  }

  /** 블록: metadata는 블록 종류와 부모(목록을 부른 블록·페이지 id). */
  RawMutation block(String parentBlockId, JsonNode block) {
    ObjectNode metadata =
        JsonNodeFactory.instance
            .objectNode()
            .put("type", block.get("type").asString())
            .put("parentBlockId", parentBlockId);
    return mutation(Resource.BLOCK, block, metadata);
  }

  /** database·data source: metadata는 비어 있다. */
  RawMutation container(Resource resource, JsonNode container) {
    return mutation(resource, container, JsonNodeFactory.instance.objectNode());
  }

  private RawMutation mutation(Resource resource, JsonNode value, ObjectNode metadata) {
    String edited = value.get("last_edited_time").asString();
    Instant editedAt = timestamp(edited);
    SourceOrder order = new SourceOrder(resource.scheme, position(edited));
    Instant observedAt = clock.instant();
    String externalId = resource.prefix + value.get("id").asString();
    if (value.get("in_trash").asBoolean())
      return new RawMutation.Delete(
          externalId,
          Optional.of(TRASHED + edited),
          Optional.of(order),
          Optional.of(editedAt),
          observedAt);
    return new RawMutation.Upsert(
        new RawItem(
            externalId,
            resource.resourceType,
            edited,
            Optional.of(order),
            Optional.of(value.get("created_time").asString()),
            Optional.of(edited),
            observedAt,
            JsJson.stringify(metadata),
            Optional.of(capture.json(JSON_MEDIA, JsJson.stringify(value)))));
  }

  /** 원본 순서 position: 편집 시각을 TS `new Date(v).toISOString()`처럼 밀리초 UTC로 정규화한 것. */
  static String position(String lastEditedTime) {
    return ISO_MILLIS.format(timestamp(lastEditedTime));
  }

  /**
   * TS `new Date(value)`가 읽는 Notion 시각: 오프셋이 있는 ISO 8601 날짜·시각 또는 날짜만(UTC). 밀리초 아래는 버린다. 읽을 수 없으면
   * {@link NotionProtocolException}(TS "Invalid Notion page timestamp"). Notion API는 늘 `Z` 시각을 준다 —
   * 오프셋 없는 날짜·시각(JS는 지역 시각으로 읽는다)은 받지 않는다(CONTRACTS F17-D2).
   */
  static Instant timestamp(String value) {
    try {
      return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          .toInstant()
          .truncatedTo(ChronoUnit.MILLIS);
    } catch (DateTimeParseException notDateTime) {
      try {
        return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
      } catch (DateTimeParseException notDate) {
        throw new NotionProtocolException(INVALID_TIMESTAMP);
      }
    }
  }
}
