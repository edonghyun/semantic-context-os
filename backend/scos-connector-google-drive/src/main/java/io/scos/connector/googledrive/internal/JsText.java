package io.scos.connector.googledrive.internal;

import io.scos.engine.support.PureFunctions;
import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Drive 투영기의 관대한 값 읽기와 JS 문자열 의미: object(객체가 아니면 빈 객체)·objects(배열이 아니면 빈 배열, 원소도 object)·text(문자열이
 * 아니면 "")·엄격 같음·JS trim(공백 집합이 Java와 다르다 — NBSP·BOM·U+2028을 뗀다). 참 값·{@code String(v)}·{@code
 * Number(v)}는 수집과 같은 {@link JsValues}의 것이다.
 */
@PureFunctions
public final class JsText {
  private static final JsonNode EMPTY = JsonNodeFactory.instance.objectNode();

  private JsText() {}

  /** 객체(배열 아님)면 그대로, 아니면 빈 객체. */
  public static JsonNode object(@Nullable JsonNode value) {
    return value != null && value.isObject() ? value : EMPTY;
  }

  /** 배열이면 원소마다 object, 아니면 빈 목록. */
  public static List<JsonNode> objects(JsonNode value) {
    List<JsonNode> out = new ArrayList<>();
    if (value != null && value.isArray()) for (JsonNode v : value) out.add(object(v));
    return out;
  }

  /** 문자열이면 그대로, 아니면 "". */
  public static String text(JsonNode value) {
    return value != null && value.isString() ? value.asString() : "";
  }

  /** 문자열이면 그대로, 아니면 대체값. */
  public static String isString(JsonNode value, String otherwise) {
    return value != null && value.isString() ? value.asString() : otherwise;
  }

  /** JS 참 값인지(`if (v)`). 파싱한 JSON의 수에는 NaN이 없다. */
  public static boolean truthy(JsonNode value) {
    return JsValues.truthy(value);
  }

  public static boolean isTrue(JsonNode value) {
    return JsValues.isTrue(value);
  }

  /** JS {@code a === b}: 둘 다 없음, 같은 문자열·수·불리언, 둘 다 null. 객체·배열은 같지 않다(참조 비교). */
  public static boolean strictEquals(JsonNode a, JsonNode b) {
    boolean aAbsent = a == null || a.isMissingNode();
    boolean bAbsent = b == null || b.isMissingNode();
    if (aAbsent || bAbsent) return aAbsent && bAbsent;
    if (a.isNull() || b.isNull()) return a.isNull() && b.isNull();
    if (a.isString() && b.isString()) return a.asString().equals(b.asString());
    if (a.isNumber() && b.isNumber()) return a.asDouble() == b.asDouble();
    if (a.isBoolean() && b.isBoolean()) return a.asBoolean() == b.asBoolean();
    return false;
  }

  /** JS {@code String(v)}. */
  public static String string(JsonNode value) {
    return JsValues.string(value);
  }

  /** JS {@code array.join(sep)}: null·undefined 원소는 "". */
  public static String join(JsonNode array, String separator) {
    List<String> parts = new ArrayList<>();
    for (JsonNode v : array) parts.add(v == null || v.isNull() ? "" : string(v));
    return String.join(separator, parts);
  }

  /** JS 수 표기(NaN·Infinity 포함). */
  static String number(double value) {
    if (Double.isNaN(value)) return "NaN";
    if (value == Double.POSITIVE_INFINITY) return "Infinity";
    if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
    return JsJson.stringify(DoubleNode.valueOf(value));
  }

  /** JS {@code Number(v)}(undefined는 NaN, null은 0). */
  static double toNumber(JsonNode value) {
    return JsValues.number(value);
  }

  static String trim(String s) {
    return Text.jsTrim(s);
  }

  static String trimEnd(String s) {
    return Text.jsTrimEnd(s);
  }
}
