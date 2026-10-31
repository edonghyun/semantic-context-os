package io.scos.connector.tiro.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Tiro 응답 값의 모양 검사. 위반은 invalid_provider_response({@link TiroProtocolException#invalid}). */
final class TiroJson {
  private TiroJson() {}

  static ObjectNode object(JsonNode value) {
    if (value == null || !value.isObject())
      throw TiroProtocolException.invalid("Invalid Tiro object");
    return (ObjectNode) value;
  }

  /** `{content: "<문자열>", ...}` 객체인가. */
  static boolean isText(JsonNode value) {
    return value != null
        && value.isObject()
        && value.get("content") != null
        && value.get("content").isString();
  }

  /** JS `value != null`: 키가 없거나 null이면 없음. */
  static boolean present(JsonNode value) {
    return value != null && !value.isNull();
  }

  static boolean isString(JsonNode value) {
    return value != null && value.isString();
  }
}
