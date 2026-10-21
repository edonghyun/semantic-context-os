package io.scos.connector.googledrive;

import io.scos.connector.googledrive.internal.DocsRenderer;
import io.scos.connector.googledrive.internal.JsText;
import io.scos.connector.googledrive.internal.SheetsRenderer;
import io.scos.connector.googledrive.internal.SlidesRenderer;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drive 원본 → 문서(TS source-projector/src/google-drive-projector.ts). 파일(`gdrive.file`)과 공유
 * 드라이브(`gdrive.drive`) 원본 하나가 문서 하나이고, 시트 범위 원본(`gdrive.sheet`)은 metadata.fileId의 파일 문서에 붙는다. 바이너리
 * 원본은 읽지 않는다.
 *
 * <p>본문(문단을 빈 줄로 잇고 빈 문단은 뺀다): `# 제목`(없으면 문서 id), 설명, `파일 형식: …`, `상위 폴더 ID: …`, `바로가기 대상: …`, 추출
 * 종류별 본문 — Docs(탭·하위 탭·머리말·꼬리말·각주), Sheets(시트 제목 + 범위마다 `A1: 값 (수식: …) (메모: …) (링크)` — 파일 버전과 같은
 * 범위만, 나머지는 `## 수집 제한` 줄), Slides(슬라이드·표·그룹·발표자 노트), 텍스트 — 그다음 `## 댓글`(답글은 ` ↳`), `## 수집 제한`.
 *
 * <p>TS와 같은 관대한 읽기: 객체가 아닌 값은 빈 객체, 배열이 아닌 값은 빈 배열, 문자열이 아닌 값은 빈 문자열로 본다(투영이 실패하지 않는다). 객체 값을 도는
 * 순서·trim·수 표기는 JS와 같다({@link JsText}).
 */
public final class GoogleDriveProjector implements Projector {
  public static final String KIND = "google-drive";
  static final String FILE = "gdrive.file";
  static final String DRIVE = "gdrive.drive";
  static final String SHEET = "gdrive.sheet";
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final ObjectMapper json;

  public GoogleDriveProjector(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public String kind() {
    return KIND;
  }

  /** 파일·드라이브 원본마다 문서 하나, 시트 범위 원본은 그 파일 문서 뒤에(파일이 없으면 버린다). */
  @Override
  public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
    Map<String, List<RawItem>> groups = new LinkedHashMap<>();
    for (RawItem item : items)
      if (item.resourceType().equals(FILE) || item.resourceType().equals(DRIVE)) {
        List<RawItem> group = new ArrayList<>();
        group.add(item);
        groups.put(item.externalId(), group);
      }
    for (RawItem item : items)
      if (item.resourceType().equals(SHEET)) {
        List<RawItem> group =
            groups.get(
                "file:"
                    + JsText.text(JsText.object(json.readTree(item.metadataJson())).get("fileId")));
        if (group != null) group.add(item);
      }
    return new TreeMap<>(groups);
  }

  @Override
  public Optional<SourceDocument> project(
      String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
    if (items.isEmpty()) return Optional.empty();
    RawItem item = items.getFirst();
    boolean drive = item.resourceType().equals(DRIVE);
    if (!drive && !item.resourceType().equals(FILE)) return Optional.empty();
    JsonNode payload = read(item, reader);
    JsonNode file = drive ? payload : JsText.object(payload.get("file"));
    if (!JsText.truthy(file.get("id"))) return Optional.empty();
    String title = JsText.text(file.get("name"));
    if (title.isEmpty()) title = documentId;
    List<String> lines = new ArrayList<>();
    lines.add("# " + title);
    if (JsText.truthy(file.get("description"))) lines.add(JsText.text(file.get("description")));
    if (JsText.truthy(file.get("mimeType")))
      lines.add("파일 형식: " + JsText.text(file.get("mimeType")));
    JsonNode parents = file.get("parents");
    if (parents != null && parents.isArray() && !parents.isEmpty())
      lines.add("상위 폴더 ID: " + JsText.join(parents, ", "));
    JsonNode targetId = JsText.object(file.get("shortcutDetails")).get("targetId");
    if (JsText.truthy(targetId))
      lines.add("바로가기 대상: https://drive.google.com/file/d/" + component(JsText.string(targetId)));
    JsonNode workspace = JsText.object(payload.get("workspace"));
    String extraction = JsText.isString(payload.get("extraction"), "");
    switch (extraction) {
      case "google_docs" -> lines.add(DocsRenderer.document(workspace));
      case "google_sheets" -> {
        lines.add(SheetsRenderer.spreadsheet(workspace));
        sheetRanges(payload, file, items, reader, lines);
      }
      case "google_slides" -> lines.add(SlidesRenderer.presentation(workspace));
      case "text" -> lines.add(JsText.text(payload.get("text")));
      default -> {}
    }
    List<JsonNode> comments = undeleted(JsText.objects(payload.get("comments")));
    if (!comments.isEmpty()) {
      lines.add("## 댓글");
      for (JsonNode comment : comments) {
        lines.add(author(comment) + ": " + JsText.text(comment.get("content")));
        for (JsonNode reply : undeleted(JsText.objects(comment.get("replies"))))
          lines.add("  ↳ " + author(reply) + ": " + JsText.text(reply.get("content")));
      }
    }
    JsonNode limitations = payload.get("limitations");
    if (limitations != null && limitations.isArray() && !limitations.isEmpty())
      // TS limitations.map(String).join — null 원소도 "null"이다(Array#join과 다르다)
      lines.add(
          "## 수집 제한\n" + String.join("\n", limitations.valueStream().map(JsText::string).toList()));
    String url = JsText.text(JsText.object(json.readTree(item.metadataJson())).get("url"));
    String observedAt = ISO_MILLIS.format(item.observedAt());
    return Optional.of(
        new SourceDocument(
            sourceId,
            documentId,
            url.isEmpty() ? "gdrive://" + sourceId + "/" + documentId : url,
            title,
            String.join("\n\n", lines.stream().filter(l -> !l.isEmpty()).toList()),
            item.sourceCreatedAt().orElse(observedAt),
            item.sourceUpdatedAt().orElse(observedAt)));
  }

  /** 범위 원본마다 파일 버전과 같으면 렌더링하고, 없거나 다르면 센다. */
  private void sheetRanges(
      JsonNode payload,
      JsonNode file,
      List<RawItem> items,
      ContentReader reader,
      List<String> lines) {
    JsonNode keys = payload.get("sheetRangeKeys");
    if (keys == null || !keys.isArray()) return;
    Map<String, RawItem> parts = new LinkedHashMap<>();
    for (RawItem part : items.subList(1, items.size())) parts.put(part.externalId(), part);
    int missing = 0;
    for (JsonNode key : keys) {
      RawItem part = parts.get(JsText.string(key));
      JsonNode range = part == null ? null : read(part, reader);
      if (range == null || !JsText.strictEquals(range.get("version"), file.get("version"))) {
        missing++;
        continue;
      }
      lines.add(SheetsRenderer.spreadsheet(JsText.object(range.get("workspace"))));
    }
    if (missing > 0) lines.add("## 수집 제한\n시트 범위 " + missing + "개 미수집 또는 버전 불일치");
  }

  /** 원본 JSON(TS object(await readContent(item))): 본문이 없거나 객체가 아니면 빈 객체. */
  private JsonNode read(RawItem item, ContentReader reader) {
    return JsText.object(reader.apply(item).map(json::readTree).orElse(null));
  }

  private static List<JsonNode> undeleted(List<JsonNode> entries) {
    return entries.stream().filter(e -> !JsText.isTrue(e.get("deleted"))).toList();
  }

  private static String author(JsonNode entry) {
    return JsText.text(JsText.object(entry.get("author")).get("displayName"));
  }

  /** encodeURIComponent. */
  private static String component(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~");
  }
}
