package io.scos.connector.jira.internal;

import io.scos.engine.support.text.JsJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Jira 값 평문화(TS jira-projector.ts renderJiraValue): ADF와 일반 Jira 값을 검색용 텍스트로. 전송 URL·계정 id는 산문으로
 * 내보내지 않는다. 출력은 TS와 바이트 단위로 같다(F5 T5.4, 골든 expected-adf.json).
 *
 * <ul>
 *   <li>없음·null → "", 문자열·수·불리언 → JS {@code String(값)}, 배열 → 원소마다 평문화해 빈 것을 빼고 줄바꿈으로.
 *   <li>text: 문자열이 아니면 "". 첫 link 표식의 href가 http(s)면 {@code [text](href)}.
 *   <li>hardBreak → 줄바꿈, mention → attrs.text, emoji → attrs.text ?? attrs.shortName,
 *       inlineCard·blockCard → attrs.url.
 *   <li>content 배열이 있으면 자식을 이어 붙인다 — paragraph·heading은 그대로, tableRow는 " | ", 그 밖은 줄바꿈. listItem은
 *       앞에 "- ".
 *   <li>노드 이름 비교는 TS처럼 paragraph·heading만 {@code String(type)}이고, 나머지(text·link 표식 포함)는 문자열 type일
 *       때만 같다.
 *   <li>그 밖의 객체: displayName·name·value·filename·title 중 첫 문자열, 없으면
 *       self·id·accountId·avatarUrls·emailAddress를 뺀 "키: 값" 줄(JS 키 순서 — 정수 키가 먼저 오름차순).
 * </ul>
 */
public final class JiraValueRenderer {
  private static final Pattern HTTP = Pattern.compile("^https?://");
  private static final List<String> NAME_KEYS =
      List.of("displayName", "name", "value", "filename", "title");
  private static final Set<String> HIDDEN_KEYS =
      Set.of("self", "id", "accountId", "avatarUrls", "emailAddress");

  private JiraValueRenderer() {}

  public static String render(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return "";
    if (value.isString()) return value.asString();
    if (value.isNumber()) return JsJson.stringify(value);
    if (value.isBoolean()) return String.valueOf(value.asBoolean());
    if (value.isArray()) {
      List<String> parts = new ArrayList<>();
      for (JsonNode element : value) {
        String text = render(element);
        if (!text.isEmpty()) parts.add(text);
      }
      return String.join("\n", parts);
    }
    // TS는 paragraph·heading만 String(type)으로, 나머지 노드는 type === "…"(문자열일 때만)으로 가린다
    String type = strictType(value);
    if (type.equals("text")) return text(value);
    Optional<JsonNode> attrs = object(value.get("attrs"));
    switch (type) {
      case "hardBreak" -> {
        return "\n";
      }
      case "mention" -> {
        return attrs
            .map(a -> a.get("text"))
            .filter(JsonNode::isString)
            .map(JsonNode::asString)
            .orElse("");
      }
      case "emoji" -> {
        return jsString(
            attrs
                .map(a -> nullish(a.get("text")) ? a.get("shortName") : a.get("text"))
                .orElse(null));
      }
      case "inlineCard", "blockCard" -> {
        return jsString(attrs.map(a -> a.get("url")).orElse(null));
      }
      default -> {
        // 아래에서 content·이름·일반 객체 순으로 본다
      }
    }
    JsonNode content = value.get("content");
    if (content != null && content.isArray()) {
      List<String> children = new ArrayList<>();
      content.forEach(child -> children.add(render(child)));
      String separator =
          List.of("paragraph", "heading").contains(jsString(value.get("type")))
              ? ""
              : type.equals("tableRow") ? " | " : "\n";
      String joined = String.join(separator, children);
      return type.equals("listItem") ? "- " + joined : joined;
    }
    for (String key : NAME_KEYS) {
      JsonNode name = value.get(key);
      if (name != null && name.isString()) return name.asString();
    }
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, JsonNode> entry : JsJson.entries(value)) {
      if (HIDDEN_KEYS.contains(entry.getKey())) continue;
      String text = render(entry.getValue());
      if (!text.isEmpty()) lines.add(entry.getKey() + ": " + text);
    }
    return String.join("\n", lines);
  }

  private static String text(JsonNode node) {
    JsonNode raw = node.get("text");
    String text = raw != null && raw.isString() ? raw.asString() : "";
    JsonNode marks = node.get("marks");
    if (marks == null || !marks.isArray()) return text;
    for (JsonNode mark : marks) {
      Optional<JsonNode> m = object(mark);
      if (m.isEmpty() || !strictType(m.get()).equals("link")) continue;
      JsonNode href = object(m.get().get("attrs")).map(a -> a.get("href")).orElse(null);
      return href != null && href.isString() && HTTP.matcher(href.asString()).find()
          ? "[" + text + "](" + href.asString() + ")"
          : text;
    }
    return text;
  }

  /** JS {@code node.type === "…"}의 왼쪽: 문자열 type만, 아니면 ""(어느 노드 이름과도 같지 않다). */
  private static String strictType(JsonNode node) {
    JsonNode type = node.get("type");
    return type != null && type.isString() ? type.asString() : "";
  }

  private static Optional<JsonNode> object(JsonNode node) {
    return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
  }

  /** JS `x ?? …`의 왼쪽이 비었는가(없음·null). */
  private static boolean nullish(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode();
  }

  /** JS 템플릿 문자열 {@code `${x}`}: 없음은 "undefined", null은 "null", 나머지는 {@code String(x)}. */
  public static String templateString(JsonNode node) {
    if (node == null || node.isMissingNode()) return "undefined";
    if (node.isNull()) return "null";
    return jsString(node);
  }

  /** JS {@code String(x ?? "")}: 없음·null은 "", 수는 JS 표기, 배열은 쉼표로 이은 원소, 객체는 "[object Object]". */
  private static String jsString(JsonNode node) {
    if (nullish(node)) return "";
    if (node.isString()) return node.asString();
    if (node.isNumber()) return JsJson.stringify(node);
    if (node.isBoolean()) return String.valueOf(node.asBoolean());
    if (!node.isArray()) return "[object Object]";
    // JS String(배열) = Array#join(","): 원소마다 String, null·undefined는 빈 문자열
    List<String> parts = new ArrayList<>();
    node.forEach(element -> parts.add(jsString(element)));
    return String.join(",", parts);
  }
}
