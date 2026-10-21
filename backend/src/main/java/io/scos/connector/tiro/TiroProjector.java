package io.scos.connector.tiro;

import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tiro 노트 원본(`tiro.note`) → 회의 문서 하나(TS source-projector/src/tiro-projector.ts). 문서 id는 원본
 * externalId(`note:<guid>`). 본문 순서: `# 제목`, 접근 제한 줄, 회의 일시, `## AI 요약`, `## <템플릿 제목>`(회의 문서 섹션),
 * `## 전사 원문`(화자 구간이 있으면 `이름: 발화`, 없으면 전사), 잠긴 문단 수. 잠긴 문단과 참여자·협업자 정보는 본문에 넣지 않는다 — AI 요약은 화자 귀속
 * 전사와 구분한다. uri는 `https://tiro.ooo/`로 시작하는 webUrl, 아니면 `tiro://<documentId>`.
 *
 * <p>TS와의 차이: TS는 제목·템플릿 제목·화자 이름이 문자열이 아니면 `String(...)`으로 바꾸지만, 여기서는 투영 실패로 본다(수집이 문자열을 보장한다 —
 * 다르면 원본이 손상된 것이다).
 */
public final class TiroProjector implements Projector {
  public static final String KIND = "tiro";
  static final String RESOURCE_TYPE = "tiro.note";
  static final String TIRO_ORIGIN = "https://tiro.ooo/";
  private static final Map<String, String> LABELS =
      Map.of("paragraphs", "전사 원문", "summaries", "AI 요약", "documents", "회의 문서");
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final ObjectMapper json;

  public TiroProjector(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public String kind() {
    return KIND;
  }

  /** 노트 원본 하나가 문서 하나다. */
  @Override
  public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
    Map<String, List<RawItem>> groups = new TreeMap<>();
    for (RawItem item : items)
      if (item.resourceType().equals(RESOURCE_TYPE)) groups.put(item.externalId(), List.of(item));
    return groups;
  }

  @Override
  public Optional<SourceDocument> project(
      String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
    if (items.isEmpty() || !items.getFirst().resourceType().equals(RESOURCE_TYPE))
      return Optional.empty();
    RawItem item = items.getFirst();
    JsonNode raw =
        object(json.readTree(reader.apply(item).orElseThrow(() -> invalid("projection content"))));
    JsonNode note = object(raw.get("note"));
    String title = string(note.get("title"), "note title");
    List<String> lines = new ArrayList<>();
    lines.add("# " + title);
    List<String> unavailable = new ArrayList<>();
    JsonNode resources = raw.get("unavailableResources");
    if (resources != null && resources.isArray())
      for (JsonNode kind : resources) if (kind.isString()) unavailable.add(kind.asString());
    if (!unavailable.isEmpty())
      lines.add(
          "접근 제한으로 제외된 내용: "
              + String.join(
                  ", ", unavailable.stream().map(k -> LABELS.getOrDefault(k, k)).toList()));
    JsonNode recordingStartAt = note.get("recordingStartAt");
    if (recordingStartAt != null && recordingStartAt.isString())
      lines.add("회의 일시: " + recordingStartAt.asString());
    for (JsonNode summary : entries(raw.get("summaries"))) {
      String content = text(summary.get("content"));
      if (!content.isEmpty()) lines.add("## AI 요약\n" + content);
    }
    for (JsonNode document : entries(raw.get("documents"))) {
      List<String> sections = new ArrayList<>();
      for (JsonNode section : entries(document.get("sections"))) {
        String content = text(section.get("content"));
        if (!content.isEmpty()) sections.add(content);
      }
      if (!sections.isEmpty())
        lines.add(
            "## " + templateTitle(document.get("template")) + "\n" + String.join("\n\n", sections));
    }
    List<String> transcript = new ArrayList<>();
    int locked = 0;
    for (JsonNode paragraph : entries(raw.get("paragraphs"))) {
      JsonNode isLocked = paragraph.get("locked");
      if (isLocked != null && isLocked.isBoolean() && isLocked.asBoolean()) {
        locked++;
        continue;
      }
      String content = paragraphText(paragraph);
      if (content.isEmpty()) continue;
      JsonNode timeFrom = paragraph.get("timeFrom");
      transcript.add(
          (timeFrom != null && timeFrom.isString() ? "[" + timeFrom.asString() + "]\n" : "")
              + content);
    }
    if (!transcript.isEmpty()) lines.add("## 전사 원문\n" + String.join("\n\n", transcript));
    if (locked > 0) lines.add("전사 제한: " + locked + "개 문단은 Tiro에서 잠겨 있어 제외됨.");
    JsonNode webUrl = note.get("webUrl");
    String uri =
        webUrl != null && webUrl.isString() && webUrl.asString().startsWith(TIRO_ORIGIN)
            ? webUrl.asString()
            : "tiro://" + documentId;
    String observedAt = ISO_MILLIS.format(item.observedAt());
    return Optional.of(
        new SourceDocument(
            sourceId,
            documentId,
            uri,
            title,
            String.join("\n\n", lines),
            item.sourceCreatedAt().orElse(observedAt),
            item.sourceUpdatedAt().orElse(observedAt)));
  }

  /** 화자 구간이 있으면 `이름(없으면 라벨): 발화` 줄들, 없으면 전사 본문. */
  private String paragraphText(JsonNode paragraph) {
    JsonNode segments = paragraph.get("diarizedSegments");
    List<JsonNode> list = segments == null || segments.isNull() ? List.of() : entries(segments);
    if (list.isEmpty()) return text(paragraph.get("transcript"));
    List<String> out = new ArrayList<>();
    for (JsonNode segment : list) {
      JsonNode speaker = object(segment.get("speaker"));
      String content = string(segment.get("content"), "speaker segment");
      JsonNode personName = speaker.get("personName");
      String name =
          personName == null || personName.isNull()
              ? string(speaker.get("label"), "speaker label")
              : string(personName, "speaker name");
      out.add(name + ": " + content);
    }
    return String.join("\n", out);
  }

  /**
   * 템플릿이 JS falsy(없음·null·false·0·"")면 기본 제목(TS `document.template ? object(…) : {}`), 그 밖에 객체가 아니면
   * 위반.
   */
  private static String templateTitle(JsonNode template) {
    if (isFalsy(template)) return "AI 회의 문서";
    JsonNode title = object(template).get("title");
    return title == null || title.isNull() ? "AI 회의 문서" : string(title, "template title");
  }

  private static boolean isFalsy(JsonNode value) {
    return value == null
        || value.isNull()
        || (value.isBoolean() && !value.asBoolean())
        || (value.isNumber() && value.asDouble() == 0)
        || (value.isString() && value.asString().isEmpty());
  }

  /** TS text: 없거나 null이면 "", 아니면 `{content: 문자열}`. */
  private static String text(JsonNode value) {
    if (value == null || value.isNull()) return "";
    return string(object(value).get("content"), "text content");
  }

  private static List<JsonNode> entries(JsonNode value) {
    if (value == null || !value.isArray()) throw invalid("collection content");
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode entry : value) out.add(object(entry));
    return out;
  }

  private static JsonNode object(JsonNode value) {
    if (value == null || !value.isObject()) throw invalid("projection content");
    return value;
  }

  private static String string(JsonNode value, String what) {
    if (value == null || !value.isString()) throw invalid(what);
    return value.asString();
  }

  private static IllegalArgumentException invalid(String what) {
    return new IllegalArgumentException("Invalid Tiro " + what);
  }
}
