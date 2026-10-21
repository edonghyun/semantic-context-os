package io.scos.connector.googledrive.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.JsStrings;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * TS가 응답 값을 다루는 JS 의미 — {@code String(값)}과 {@code typeof 값 === "string"}처럼 Drive 어댑터가 비교·식별에 쓰는 것만.
 * 없는 값(Java null)은 JS undefined다.
 */
final class JsValues {
  /** JS `\\s`·`String#trim`이 공백으로 보는 문자(공용 {@link JsStrings#WHITESPACE}). */
  static final String JS_WHITESPACE = JsStrings.WHITESPACE;

  private JsValues() {}

  /** TS googleObject: 객체가 아니면(null·배열 포함) "Invalid Google API object". */
  static JsonNode object(JsonNode value) {
    if (value == null || !value.isObject())
      throw new GoogleProtocolException("Invalid Google API object");
    return value;
  }

  /** JS {@code Number(값)}: 문자열은 앞뒤 공백을 뗀 10진·16진·(±)Infinity(빈 값은 0), 수는 그대로, 그 밖은 NaN(null은 0). */
  static double number(JsonNode value) {
    if (value == null || value.isMissingNode()) return Double.NaN;
    if (value.isNull()) return 0;
    if (value.isNumber()) return value.asDouble();
    if (value.isBoolean()) return value.asBoolean() ? 1 : 0;
    if (!value.isString() && !value.isArray()) return Double.NaN;
    return JsStrings.number(string(value));
  }

  /** JS {@code Number.isInteger}: 유한한 수이고 소수부가 없다(1.0·1e21 포함). */
  static boolean isInteger(JsonNode value) {
    if (value == null || !value.isNumber()) return false;
    double d = value.asDouble();
    return Double.isFinite(d) && d == Math.rint(d);
  }

  /** 문자열이고 이 값과 같다(TS `v === "…"`). */
  static boolean is(JsonNode value, String expected) {
    return value != null && value.isString() && value.asString().equals(expected);
  }

  /** 거짓 false다(TS `v === false`). */
  static boolean isFalse(JsonNode value) {
    return value != null && value.isBoolean() && !value.asBoolean();
  }

  /** 참 true다(TS `v === true`). */
  static boolean isTrue(JsonNode value) {
    return value != null && value.isBoolean() && value.asBoolean();
  }

  /** JS {@code String(값)}: undefined·null·수(JS 표기)·불리언·배열(원소를 쉼표로, null·undefined는 빈 문자열)·객체. */
  static String string(JsonNode value) {
    if (value == null || value.isMissingNode()) return "undefined";
    if (value.isNull()) return "null";
    if (value.isString()) return value.asString();
    if (value.isNumber()) return JsJson.stringify(value);
    if (value.isBoolean()) return Boolean.toString(value.asBoolean());
    if (value.isArray()) {
      List<String> parts = new ArrayList<>();
      for (JsonNode element : value)
        parts.add(element == null || element.isNull() ? "" : string(element));
      return String.join(",", parts);
    }
    return "[object Object]";
  }

  /** 문자열이고 비어 있지 않다(TS `typeof v === "string" && v`). */
  static boolean nonEmptyString(JsonNode value) {
    return value != null && value.isString() && !value.asString().isEmpty();
  }

  /** JS 참 값인지(TS `if (v)`): undefined·null·false·0·NaN·""만 거짓. */
  static boolean truthy(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) return false;
    if (value.isBoolean()) return value.asBoolean();
    if (value.isNumber()) return value.asDouble() != 0;
    if (value.isString()) return !value.asString().isEmpty();
    return true;
  }
}
