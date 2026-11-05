package io.scos.connector.tiro.internal;

import static io.scos.connector.tiro.internal.TiroJson.isString;
import static io.scos.connector.tiro.internal.TiroJson.isText;
import static io.scos.connector.tiro.internal.TiroJson.object;
import static io.scos.connector.tiro.internal.TiroJson.present;
import static io.scos.connector.tiro.internal.TiroProtocolException.invalid;

import io.scos.connector.tiro.internal.TiroCollection.Scope;
import io.scos.connector.tiro.internal.TiroCollection.UnavailableNote;
import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 노트 하나를 원본 하나(`note:<guid>`, `tiro.note`)로 엮는다: 상세 → 전사 문단(전 페이지) → 요약 → 회의 문서. 상세의 403·404는 그 노트의
 * 삭제다. 문단·요약·문서 경로의 403·404는 그 종류만 비우고 `unavailableResources`에 남긴다. sourceVersion = sha256(직렬화한
 * 본문), sourceUpdatedAt은 노트·요약·문서 중 가장 늦은 updatedAt, 잠긴 문단 수는 메타데이터 `lockedParagraphs`로만 남긴다.
 */
final class TiroNoteReader {
  static final String RESOURCE_TYPE = "tiro.note";
  static final int PARAGRAPH_PAGE_SIZE = 100;

  /** 노트 아래 자원 종류(경로 조각이자 `unavailableResources` 값). */
  enum Resource {
    PARAGRAPHS("paragraphs"),
    SUMMARIES("summaries"),
    DOCUMENTS("documents");

    final String path;

    Resource(String path) {
      this.path = path;
    }
  }

  @FunctionalInterface
  private interface ResourceRead {
    ArrayNode read();
  }

  private final TiroCollection reads;
  private final ContentCapture contents;
  private final TiroConfiguration config;
  private final ObjectMapper json;
  private final Clock clock;

  TiroNoteReader(
      TiroCollection reads,
      ContentCapture contents,
      TiroConfiguration config,
      ObjectMapper json,
      Clock clock) {
    this.reads = reads;
    this.contents = contents;
    this.config = config;
    this.json = json;
    this.clock = clock;
  }

  /** 노트 하나의 변경: 읽을 수 있으면 upsert, 상세가 403·404면 delete. */
  RawMutation read(String guid) {
    String externalId = "note:" + guid;
    String path = "/v1/external/notes/" + guid;
    ObjectNode note;
    try {
      note = object(reads.read(path, Map.of(), Scope.NOTE));
    } catch (UnavailableNote e) {
      return new RawMutation.Delete(
          externalId, Optional.empty(), Optional.empty(), Optional.empty(), clock.instant());
    }
    requireNoteInWorkspace(note, guid);
    ArrayNode unavailable = json.createArrayNode();
    ArrayNode paragraphs = available(Resource.PARAGRAPHS, unavailable, () -> paragraphs(path));
    ArrayNode summaries =
        available(Resource.SUMMARIES, unavailable, () -> details(path, Resource.SUMMARIES, guid));
    ArrayNode documents =
        available(Resource.DOCUMENTS, unavailable, () -> details(path, Resource.DOCUMENTS, guid));
    return new RawMutation.Upsert(
        item(externalId, note, paragraphs, summaries, documents, unavailable));
  }

  private void requireNoteInWorkspace(ObjectNode note, String guid) {
    if (!isStringEqual(note.get("guid"), guid)
        || !isStringEqual(note.get("workspaceGuid"), config.workspaceGuid()))
      throw invalid("Tiro returned a note outside the configured workspace");
    for (String key : List.of("title", "createdAt", "updatedAt", "webUrl"))
      if (!isString(note.get(key))) throw invalid("Invalid Tiro note response");
  }

  private RawItem item(
      String externalId,
      ObjectNode note,
      ArrayNode paragraphs,
      ArrayNode summaries,
      ArrayNode documents,
      ArrayNode unavailable) {
    ObjectNode value = json.createObjectNode();
    value.set("note", note);
    value.set("paragraphs", paragraphs);
    value.set("summaries", summaries);
    value.set("documents", documents);
    value.set("unavailableResources", unavailable);
    String valueJson = JsJson.stringify(value);
    int locked = 0;
    for (JsonNode p : paragraphs) if (p.get("locked").asBoolean()) locked++;
    List<ObjectNode> updated = new ArrayList<>();
    updated.add(note);
    summaries.forEach(s -> updated.add((ObjectNode) s));
    documents.forEach(d -> updated.add((ObjectNode) d));
    ObjectNode metadata = json.createObjectNode();
    metadata.put("url", note.get("webUrl").asString());
    metadata.put("workspaceGuid", config.workspaceGuid());
    metadata.put("lockedParagraphs", locked);
    metadata.set("unavailableResources", unavailable.deepCopy());
    RawContent content = contents.json("application/json", valueJson);
    return new RawItem(
        externalId,
        RESOURCE_TYPE,
        Text.sha256Hex(valueJson),
        Optional.empty(),
        Optional.of(note.get("createdAt").asString()),
        Optional.of(latestUpdate(updated)),
        clock.instant(),
        json.writeValueAsString(metadata),
        Optional.of(content),
        RawAcl.notObserved());
  }

  /** 자원 한 종류를 읽는다. 그 경로가 403·404면 비우고 unavailable에 종류를 더한다. */
  private ArrayNode available(Resource resource, ArrayNode unavailable, ResourceRead read) {
    try {
      return read.read();
    } catch (UnavailableNote e) {
      unavailable.add(resource.path);
      return json.createArrayNode();
    }
  }

  /** 전사 문단 전 페이지(커서가 같은 곳을 돌면 위반). */
  private ArrayNode paragraphs(String notePath) {
    ArrayNode out = json.createArrayNode();
    String path = notePath + "/" + Resource.PARAGRAPHS.path;
    CursorWalk walk =
        new CursorWalk(path, PARAGRAPH_PAGE_SIZE, "Tiro paragraph pagination did not advance");
    Optional<String> cursor = Optional.empty();
    do {
      Map<String, String> query = walk.query(cursor);
      TiroCursorPage page = TiroCursorPage.read(reads.read(path, query, Scope.NOTE));
      // 받은 커서가 이미 요청한 커서면 다음 query()가 위반으로 멈춘다 — 문단은 페이지마다 내지 않으므로 결과가 같다
      for (ObjectNode paragraph : page.entries()) {
        validateParagraph(paragraph);
        out.add(paragraph);
      }
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  /** 요약 또는 회의 문서: 목록(전부 실렸는지 totalSize로 확인) → 항목마다 상세. 잘린 문서·다른 노트의 문서·본문 없는 문서는 위반. */
  private ArrayNode details(String notePath, Resource kind, String guid) {
    String path = notePath + "/" + kind.path;
    ObjectNode listing = object(reads.read(path, Map.of(), Scope.NOTE));
    JsonNode content = listing.get("content");
    JsonNode totalSize = listing.get("totalSize");
    if (content == null
        || !content.isArray()
        || (totalSize != null && !(totalSize.isNumber() && totalSize.asDouble() == content.size())))
      throw invalid("Incomplete Tiro document listing");
    ArrayNode out = json.createArrayNode();
    for (JsonNode entry : content) {
      JsonNode id = object(entry).get("id");
      if (!isSafeNonNegativeInteger(id)) throw invalid("Invalid Tiro document identity");
      long number = (long) id.asDouble();
      ObjectNode detail = object(reads.read(path + "/" + number, Map.of(), Scope.NOTE));
      JsonNode detailId = detail.get("id");
      JsonNode truncated = detail.get("truncated");
      if (detailId == null
          || !detailId.isNumber()
          || detailId.asDouble() != number
          || !isStringEqual(detail.get("noteGuid"), guid)
          || (truncated != null && truncated.isBoolean() && truncated.asBoolean()))
        throw invalid("Invalid Tiro document response");
      if (kind == Resource.DOCUMENTS ? !hasTextSections(detail) : !isText(detail.get("content")))
        throw invalid("Invalid Tiro document content");
      out.add(detail);
    }
    return out;
  }

  private static boolean hasTextSections(ObjectNode document) {
    JsonNode sections = document.get("sections");
    if (sections == null || !sections.isArray()) return false;
    for (JsonNode section : sections) if (!isText(object(section).get("content"))) return false;
    return true;
  }

  /** 문단: uuid 문자열·locked 불리언, 잠기지 않았으면 전사(있으면 텍스트)와 화자 구간(있으면 content·speaker.label 문자열). */
  private static void validateParagraph(ObjectNode paragraph) {
    JsonNode uuid = paragraph.get("uuid");
    JsonNode locked = paragraph.get("locked");
    if (uuid == null || !uuid.isString() || locked == null || !locked.isBoolean())
      throw invalid("Invalid Tiro paragraph response");
    if (locked.asBoolean()) return;
    if (present(paragraph.get("transcript")) && !isText(paragraph.get("transcript")))
      throw invalid("Invalid Tiro transcript content");
    JsonNode segments = paragraph.get("diarizedSegments");
    if (!present(segments)) return;
    if (!segments.isArray()) throw invalid("Invalid Tiro speaker segments");
    for (JsonNode value : segments) {
      ObjectNode segment = object(value);
      ObjectNode speaker = object(segment.get("speaker"));
      JsonNode personName = speaker.get("personName");
      if (!isString(segment.get("content"))
          || !isString(speaker.get("label"))
          || (present(personName) && !personName.isString()))
        throw invalid("Invalid Tiro speaker segment");
    }
  }

  /** 노트·요약·문서의 updatedAt 중 가장 늦은 값(문자열 그대로). 없는 자원은 건너뛰고, 있는데 시각이 아니면 위반. 같은 시각이면 앞의 것. */
  private static String latestUpdate(List<ObjectNode> resources) {
    String latest = null;
    long latestMillis = 0;
    for (ObjectNode resource : resources) {
      JsonNode timestamp = resource.get("updatedAt");
      if (timestamp == null) continue;
      long millis = epochMillis(timestamp);
      if (latest == null || millis > latestMillis) {
        latest = timestamp.asString();
        latestMillis = millis;
      }
    }
    if (latest == null) throw invalid("Missing Tiro update timestamp");
    return latest;
  }

  /** ISO-8601 오프셋 시각(TS Date.parse가 받는 오프셋 없는 형식은 받지 않는다 — Tiro API는 date-time을 오프셋과 함께 준다). */
  private static long epochMillis(JsonNode timestamp) {
    if (!timestamp.isString()) throw invalid("Invalid Tiro update timestamp");
    try {
      return OffsetDateTime.parse(timestamp.asString()).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      throw invalid("Invalid Tiro update timestamp");
    }
  }

  /** JS Number.isSafeInteger(id) && id >= 0. */
  private static boolean isSafeNonNegativeInteger(JsonNode id) {
    if (id == null || !id.isNumber()) return false;
    double value = id.asDouble();
    return value >= 0 && value == Math.rint(value) && value <= JsJson.MAX_SAFE_INTEGER;
  }

  private static boolean isStringEqual(JsonNode node, String expected) {
    return node != null && node.isString() && node.asString().equals(expected);
  }
}
