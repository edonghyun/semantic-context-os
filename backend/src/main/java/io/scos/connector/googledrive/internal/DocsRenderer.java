package io.scos.connector.googledrive.internal;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Docs API 원본의 평문(TS renderDocument·renderDocumentSections·renderStructuralElements). 탭이 있으면 탭마다
 * `## 탭 제목`·본문· 하위 탭, 없으면 문서 본문. 본문 뒤에 머리말·꼬리말·각주(`### 머리말` 등, 항목은 JS 객체 순서). 문단은 텍스트 런(링크는
 * `[글](url)` — 끝 공백은 링크 밖으로)·리치 링크·사람 이름을 잇고, 글머리 문단은 `- `. 표는 행마다 칸을 ` | `로, 목차는 그 내용.
 */
public final class DocsRenderer {
  private static final String[][] SECTIONS = {
    {"headers", "머리말"}, {"footers", "꼬리말"}, {"footnotes", "각주"}
  };

  private DocsRenderer() {}

  public static String document(JsonNode document) {
    JsonNode tabs = document.get("tabs");
    if (tabs != null && tabs.isArray() && !tabs.isEmpty())
      return String.join("\n\n", JsText.objects(tabs).stream().map(DocsRenderer::tab).toList());
    return sections(document);
  }

  private static String tab(JsonNode tab) {
    List<String> parts = new ArrayList<>();
    parts.add("## " + JsText.text(JsText.object(tab.get("tabProperties")).get("title")));
    parts.add(sections(JsText.object(tab.get("documentTab"))));
    for (JsonNode child : JsText.objects(tab.get("childTabs"))) parts.add(tab(child));
    return joinNonEmpty(parts, "\n\n");
  }

  private static String sections(JsonNode document) {
    List<String> lines = new ArrayList<>();
    lines.add(elements(JsText.object(document.get("body")).get("content")));
    for (String[] section : SECTIONS) {
      List<String> rendered = new ArrayList<>();
      for (JsonNode value : DriveValues.values(JsText.object(document.get(section[0]))))
        rendered.add(elements(JsText.object(value).get("content")));
      String joined = joinNonEmpty(rendered, "\n");
      if (!joined.isEmpty()) lines.add("### " + section[1] + "\n" + joined);
    }
    return joinNonEmpty(lines, "\n\n");
  }

  static String elements(JsonNode value) {
    StringBuilder out = new StringBuilder();
    for (JsonNode element : JsText.objects(value)) out.append(element(element));
    return out.toString();
  }

  private static String element(JsonNode element) {
    if (JsText.truthy(element.get("paragraph"))) {
      JsonNode paragraph = JsText.object(element.get("paragraph"));
      StringBuilder content = new StringBuilder();
      for (JsonNode part : JsText.objects(paragraph.get("elements"))) content.append(part(part));
      return JsText.truthy(paragraph.get("bullet")) ? "- " + content : content.toString();
    }
    if (JsText.truthy(element.get("table"))) {
      List<String> rows = new ArrayList<>();
      for (JsonNode row : JsText.objects(JsText.object(element.get("table")).get("tableRows"))) {
        List<String> cells = new ArrayList<>();
        for (JsonNode cell : JsText.objects(row.get("tableCells")))
          cells.add(JsText.trim(elements(cell.get("content"))));
        rows.add(String.join(" | ", cells));
      }
      return String.join("\n", rows) + "\n";
    }
    if (JsText.truthy(element.get("tableOfContents")))
      return elements(JsText.object(element.get("tableOfContents")).get("content"));
    return "";
  }

  private static String part(JsonNode part) {
    JsonNode run = JsText.object(part.get("textRun"));
    String link =
        JsText.text(JsText.object(JsText.object(run.get("textStyle")).get("link")).get("url"));
    JsonNode content = run.get("content");
    if (content != null && content.isString()) {
      String text = content.asString();
      if (link.isEmpty()) return text;
      return "[" + JsText.trimEnd(text) + "](" + link + ")" + (text.endsWith("\n") ? "\n" : "");
    }
    JsonNode richLink =
        JsText.object(JsText.object(part.get("richLink")).get("richLinkProperties"));
    if (JsText.truthy(richLink.get("uri")))
      return "["
          + JsText.text(richLink.get("title"))
          + "]("
          + JsText.text(richLink.get("uri"))
          + ")";
    return JsText.text(
        JsText.object(JsText.object(part.get("person")).get("personProperties")).get("name"));
  }

  static String joinNonEmpty(List<String> parts, String separator) {
    return String.join(separator, parts.stream().filter(p -> !p.isEmpty()).toList());
  }
}
