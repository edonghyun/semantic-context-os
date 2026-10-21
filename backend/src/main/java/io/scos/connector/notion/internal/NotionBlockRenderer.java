package io.scos.connector.notion.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector.ContentReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Notion 블록 트리 → Markdown 줄(TS notion-block-renderer.ts, 바이트 동등). 부모(페이지 id 또는 접두 없는 블록 id)별 자식을
 * 생성시각 (없으면 "") → 외부 id 순(코드 유닛 비교)으로 깊이 우선 렌더링한다. 깊이마다 공백 두 칸 들여쓰기. 블록 종류별 규칙:
 *
 * <ul>
 *   <li>heading_1·2·3: `#`·`##`·`###` + 공백 + rich_text(본문이 없어도 줄을 낸다)
 *   <li>bulleted_list_item `- `, numbered_list_item `1. `, to_do `- [x] `(checked가 불리언 참일 때만)·`- [
 *       ] `
 *   <li>code: 언어(문자열일 때만)를 붙인 펜스, 본문 줄마다 들여쓰기
 *   <li>그 밖: 그 종류 키의 rich_text가 비어 있지 않으면 문단, 비면 줄을 내지 않는다(자식은 그대로 렌더링한다)
 * </ul>
 *
 * rich_text는 각 조각의 문자열 plain_text를 잇는다. 한 번 렌더링한 블록은 다시 그리지 않는다(순환 가드).
 */
public final class NotionBlockRenderer {
  static final String BLOCK_PREFIX = "block:";

  /** 블록 원본 항목의 외부 id(`block:<블록 id>`). */
  public static String blockItemId(String blockId) {
    return BLOCK_PREFIX + blockId;
  }

  private static final String INDENT_UNIT = "  ";
  private static final Comparator<RawItem> SIBLINGS =
      Comparator.comparing((RawItem block) -> block.sourceCreatedAt().orElse(""))
          .thenComparing(RawItem::externalId);

  /** TS asRecord가 객체 아닌 값에 돌려주는 빈 객체(읽기 전용으로만 쓴다). */
  private static final JsonNode EMPTY = JsonNodeFactory.instance.objectNode();

  private final ObjectMapper json;

  public NotionBlockRenderer(ObjectMapper json) {
    this.json = json;
  }

  /** 블록 원본의 metadata.type(문자열이 아니면 ""). */
  String typeOf(RawItem item) {
    JsonNode type = metadata(item).get("type");
    return type != null && type.isString() ? type.asString() : "";
  }

  /** 블록 원본의 metadata.parentBlockId(문자열이 아니면 없음). */
  public Optional<String> parentBlockIdOf(RawItem item) {
    JsonNode parent = metadata(item).get("parentBlockId");
    return parent != null && parent.isString() ? Optional.of(parent.asString()) : Optional.empty();
  }

  /** 블록을 부모 id별로 모은다(넣은 순서 — 정렬은 렌더링이 한다). 부모가 없는 블록은 버린다. */
  public Map<String, List<RawItem>> childrenByParent(List<RawItem> blocks) {
    Map<String, List<RawItem>> out = new LinkedHashMap<>();
    for (RawItem block : blocks)
      parentBlockIdOf(block)
          .ifPresent(parent -> out.computeIfAbsent(parent, p -> new ArrayList<>()).add(block));
    return out;
  }

  /** TS renderBlockTree: rootParentId 아래 블록을 줄 목록으로. */
  public List<String> render(
      String rootParentId, Map<String, List<RawItem>> childrenByParent, ContentReader reader) {
    List<String> out = new ArrayList<>();
    walk(rootParentId, 0, childrenByParent, reader, new HashSet<>(), out);
    return out;
  }

  private void walk(
      String parentId,
      int depth,
      Map<String, List<RawItem>> childrenByParent,
      ContentReader reader,
      Set<String> visited,
      List<String> out) {
    List<RawItem> siblings = new ArrayList<>(childrenByParent.getOrDefault(parentId, List.of()));
    siblings.sort(SIBLINGS);
    for (RawItem block : siblings) {
      if (!visited.add(block.externalId())) continue;
      renderBlock(typeOf(block), content(block, reader), INDENT_UNIT.repeat(depth))
          .ifPresent(out::add);
      String id = block.externalId();
      walk(
          id.startsWith(BLOCK_PREFIX) ? id.substring(BLOCK_PREFIX.length()) : id,
          depth + 1,
          childrenByParent,
          reader,
          visited,
          out);
    }
  }

  private static Optional<String> renderBlock(String type, JsonNode content, String indent) {
    return switch (type) {
      case "heading_1" -> Optional.of(indent + "# " + richText(content, type));
      case "heading_2" -> Optional.of(indent + "## " + richText(content, type));
      case "heading_3" -> Optional.of(indent + "### " + richText(content, type));
      case "bulleted_list_item" -> Optional.of(indent + "- " + richText(content, type));
      case "numbered_list_item" -> Optional.of(indent + "1. " + richText(content, type));
      case "to_do" -> Optional.of(indent + checkbox(content) + richText(content, type));
      case "code" -> Optional.of(codeFence(content, indent));
      default -> {
        String text = richText(content, type);
        yield text.isEmpty() ? Optional.empty() : Optional.of(indent + text);
      }
    };
  }

  private static String checkbox(JsonNode content) {
    JsonNode checked = record(record(content).get("to_do")).get("checked");
    return checked != null && checked.isBoolean() && checked.asBoolean() ? "- [x] " : "- [ ] ";
  }

  private static String codeFence(JsonNode content, String indent) {
    JsonNode code = record(record(content).get("code"));
    JsonNode language = code.get("language");
    StringBuilder out =
        new StringBuilder(indent)
            .append("```")
            .append(language != null && language.isString() ? language.asString() : "");
    for (String line : plainText(code.get("rich_text")).split("\n", -1))
      out.append('\n').append(indent).append(line);
    return out.append('\n').append(indent).append("```").toString();
  }

  /** TS richTextOf: content[type].rich_text의 plain_text를 잇는다. */
  private static String richText(JsonNode content, String type) {
    return plainText(record(record(content).get(type)).get("rich_text"));
  }

  private static String plainText(JsonNode richText) {
    if (richText == null || !richText.isArray()) return "";
    StringBuilder out = new StringBuilder();
    for (JsonNode entry : richText) {
      JsonNode text = entry.isObject() ? entry.get("plain_text") : null;
      if (text != null && text.isString()) out.append(text.asString());
    }
    return out.toString();
  }

  /**
   * TS titleOf: properties를 JS 순회 순서(정수 같은 키 먼저 — {@link JsJson#entries})로 보며 type이 "title"인 첫 속성의
   * 글을 잇는다. 없으면 "".
   */
  public String titleOf(Optional<String> pageContent) {
    JsonNode properties = record(record(parse(pageContent)).get("properties"));
    for (Map.Entry<String, JsonNode> property : JsJson.entries(properties)) {
      JsonNode type = record(property.getValue()).get("type");
      if (type != null && type.isString() && type.asString().equals("title"))
        return plainText(property.getValue().get("title"));
    }
    return "";
  }

  private JsonNode content(RawItem block, ContentReader reader) {
    return parse(reader.apply(block));
  }

  private JsonNode parse(Optional<String> text) {
    return text.map(json::readTree).orElse(json.nullNode());
  }

  private JsonNode metadata(RawItem item) {
    return record(json.readTree(item.metadataJson()));
  }

  /** TS asRecord: 객체가 아니면 빈 객체로 본다. */
  private static JsonNode record(JsonNode value) {
    return value != null && value.isObject() ? value : EMPTY;
  }
}
