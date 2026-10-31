package io.scos.client.redact;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * JSON 줄의 자격 증명을 가린다. 트리를 돌며 문자열 값과 필드 이름마다 평문 {@link Redaction}을 적용하고, 비밀 키 필드(`"password": "…"`)의
 * 문자열 값은 통째로 가린다. 문자열 값이 그 자체로 JSON 객체·배열이면 파싱해 같은 방식으로 들어가고, 바뀐 것이 있을 때만 다시 직렬화해 문자열에 넣는다. 그래서
 * 이스케이프가 몇 겹이든 규칙은 평문만 보고, 결과는 언제나 유효한 JSON이다. 트리가 원문 전체를 대표해야 하므로 키가 겹친 JSON은 트리로 읽지 않는다 — 겹친 키의 앞
 * 값은 트리에 남지 않기 때문이다. 스레드 안전하다.
 */
public final class JsonRedaction {
  private final Redaction text;
  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  public JsonRedaction(Redaction text) {
    this.text = text;
  }

  /** {@link Redaction#standard()} 규칙을 쓴다. */
  public static JsonRedaction standard() {
    return new JsonRedaction(Redaction.standard());
  }

  /**
   * 줄을 {@link #apply}에 넘길 트리로 읽는다. 키가 겹치거나 값 뒤에 글자가 이어진 줄은 트리가 원문 전체를 대표하지 않으므로 {@link
   * JacksonException}으로 거절한다 — 호출자는 그 줄을 원문 없이 거부한다.
   */
  public JsonNode read(String raw) {
    return json.readTree(raw);
  }

  /**
   * 원문 줄 {@code raw}와 {@link #read}로 읽은 트리 {@code tree}를 받아 가린 줄을 돌려준다. 가릴 것이 없으면 {@code raw}
   * 그대로(바이트가 같다)이고, 있으면 가린 트리를 직렬화한 줄이다.
   */
  public String apply(String raw, JsonNode tree) {
    return redact(tree).map(json::writeValueAsString).orElse(raw);
  }

  /** 가린 트리. 가릴 것이 없으면 비어 있다. 입력 트리는 바꾸지 않는다. */
  Optional<JsonNode> redact(JsonNode node) {
    if (node instanceof ObjectNode object) {
      return redactObject(object);
    }
    if (node instanceof ArrayNode array) {
      return redactArray(array);
    }
    if (node.isString()) {
      return redactString(node.asString()).map(StringNode::valueOf);
    }
    return Optional.empty();
  }

  private Optional<JsonNode> redactObject(ObjectNode object) {
    ObjectNode out = object.objectNode();
    boolean changed = false;
    for (Map.Entry<String, JsonNode> property : object.properties()) {
      String name = property.getKey();
      JsonNode value = property.getValue();
      Optional<JsonNode> redacted = redactField(name, value);
      String redactedName = text.apply(name);
      changed |= redacted.isPresent() || !redactedName.equals(name);
      out.set(redactedName, redacted.orElse(value));
    }
    return changed ? Optional.of(out) : Optional.empty();
  }

  /** 필드 이름이 값의 뜻을 정하는 경우(비밀 키, Authorization 헤더)를 먼저 보고, 아니면 값만 본다. */
  private Optional<JsonNode> redactField(String name, JsonNode value) {
    if (!value.isString()) {
      return redact(value);
    }
    String string = value.asString();
    if (SecretNames.isSecretField(name, string)) {
      return Optional.of(StringNode.valueOf(Redaction.MASK));
    }
    if (SecretNames.isAuthorizationHeader(name)) {
      String header = AuthorizationHeaderRule.applyToHeaderValue(string);
      if (!header.equals(string)) {
        return Optional.of(StringNode.valueOf(header));
      }
    }
    return redact(value);
  }

  private Optional<JsonNode> redactArray(ArrayNode array) {
    ArrayNode out = array.arrayNode(array.size());
    boolean changed = false;
    for (JsonNode element : array) {
      Optional<JsonNode> redacted = redact(element);
      changed |= redacted.isPresent();
      out.add(redacted.orElse(element));
    }
    changed |= redactArguments(out);
    return changed ? Optional.of(out) : Optional.empty();
  }

  private boolean redactArguments(ArrayNode array) {
    var arguments = new ArrayList<String>(array.size());
    for (JsonNode element : array) {
      if (!element.isString()) {
        return false;
      }
      arguments.add(element.asString());
    }
    List<String> redacted = new ArgumentRedaction(text).apply(arguments);
    if (redacted.equals(arguments)) {
      return false;
    }
    for (int i = 0; i < redacted.size(); i++) {
      array.set(i, StringNode.valueOf(redacted.get(i)));
    }
    return true;
  }

  /** JSON 객체·배열로 읽히는 문자열은 트리로 들어가고, 아니면 평문 규칙을 적용한다. 바뀐 것이 없으면 비어 있다. */
  private Optional<String> redactString(String value) {
    Optional<JsonNode> embedded = embeddedJson(value);
    if (embedded.isPresent()) {
      return redact(embedded.get()).map(json::writeValueAsString);
    }
    String redacted = text.apply(value);
    return redacted.equals(value) ? Optional.empty() : Optional.of(redacted);
  }

  /** 앞뒤 공백을 뺀 문자열이 `{…}`·`[…]`이고 JSON으로 끝까지 읽히면 그 트리. */
  private Optional<JsonNode> embeddedJson(String value) {
    String trimmed = value.strip();
    boolean object = trimmed.startsWith("{") && trimmed.endsWith("}");
    boolean array = trimmed.startsWith("[") && trimmed.endsWith("]");
    if (!object && !array) {
      return Optional.empty();
    }
    try {
      return Optional.of(json.readTree(trimmed));
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }
}
