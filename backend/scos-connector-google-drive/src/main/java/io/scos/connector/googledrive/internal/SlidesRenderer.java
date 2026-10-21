package io.scos.connector.googledrive.internal;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Slides 원본의 평문(TS renderPresentation): 슬라이드마다 `## 슬라이드 n`, 요소(도형 글·표 행마다 칸 ` | `·그룹 자식·제목·설명), 발표자
 * 노트(`### 발표자 노트` — 노트 페이지 요소 가운데 objectId가 speakerNotesObjectId와 JS `===`로 같은 것, 둘 다 없어도 같다).
 */
public final class SlidesRenderer {
  private SlidesRenderer() {}

  public static String presentation(JsonNode presentation) {
    List<String> slides = new ArrayList<>();
    List<JsonNode> list = JsText.objects(presentation.get("slides"));
    for (int index = 0; index < list.size(); index++) slides.add(slide(list.get(index), index + 1));
    return String.join("\n\n", slides);
  }

  private static String slide(JsonNode slide, int number) {
    JsonNode notesPage =
        JsText.object(JsText.object(slide.get("slideProperties")).get("notesPage"));
    JsonNode notesId = JsText.object(notesPage.get("notesProperties")).get("speakerNotesObjectId");
    List<JsonNode> speakerNotes = new ArrayList<>();
    for (JsonNode element : JsText.objects(notesPage.get("pageElements")))
      if (JsText.strictEquals(element.get("objectId"), notesId)) speakerNotes.add(element);
    String notes = elements(speakerNotes);
    List<String> parts = new ArrayList<>();
    parts.add("## 슬라이드 " + number);
    parts.add(elements(JsText.objects(slide.get("pageElements"))));
    parts.add(notes.isEmpty() ? "" : "### 발표자 노트\n" + notes);
    return DocsRenderer.joinNonEmpty(parts, "\n\n");
  }

  private static String elements(List<JsonNode> elements) {
    List<String> rendered = new ArrayList<>();
    for (JsonNode element : elements) rendered.add(element(element));
    return DocsRenderer.joinNonEmpty(rendered, "\n");
  }

  private static String element(JsonNode element) {
    String shape =
        runs(JsText.object(JsText.object(element.get("shape")).get("text")).get("textElements"));
    List<String> rows = new ArrayList<>();
    for (JsonNode row : JsText.objects(JsText.object(element.get("table")).get("tableRows"))) {
      List<String> cells = new ArrayList<>();
      for (JsonNode cell : JsText.objects(row.get("tableCells")))
        cells.add(JsText.trim(runs(JsText.object(cell.get("text")).get("textElements"))));
      rows.add(String.join(" | ", cells));
    }
    List<String> parts = new ArrayList<>();
    parts.add(shape);
    parts.add(String.join("\n", rows));
    parts.add(elements(JsText.objects(JsText.object(element.get("elementGroup")).get("children"))));
    parts.add(JsText.text(element.get("title")));
    parts.add(JsText.text(element.get("description")));
    return DocsRenderer.joinNonEmpty(parts, "\n");
  }

  private static String runs(JsonNode textElements) {
    StringBuilder out = new StringBuilder();
    for (JsonNode run : JsText.objects(textElements))
      out.append(JsText.text(JsText.object(run.get("textRun")).get("content")));
    return out.toString();
  }
}
