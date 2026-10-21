package io.scos.mcp;

import java.util.Map;
import java.util.Optional;

/**
 * tools/call 인자 읽기 — SDK가 스키마로 먼저 거르지만, 누락·타입을 한 번 더 확인해 TS(zod) 와이어와 같은 문구로 거부한다(CONTRACTS H). 검증
 * 실패는 {@link Invalid}로 던지고 핸들러가 isError로 싣는다.
 */
final class ToolArguments {
  static final class Invalid extends RuntimeException {
    Invalid(String tool, String detail) {
      super(
          "MCP error -32602: Input validation error: Invalid arguments for tool "
              + tool
              + ": "
              + detail);
    }
  }

  private final String tool;
  private final Map<String, Object> args;

  ToolArguments(String tool, Map<String, Object> args) {
    this.tool = tool;
    this.args = args == null ? Map.of() : args;
  }

  String requiredText(String name) {
    Object v = args.get(name);
    if (v == null)
      throw new Invalid(tool, "Invalid input: expected string, received undefined at " + name);
    if (!(v instanceof String s))
      throw new Invalid(
          tool, "Invalid input: expected string, received " + typeName(v) + " at " + name);
    if (s.isEmpty())
      throw new Invalid(tool, "Too small: expected string to have >=1 characters at " + name);
    return s;
  }

  Optional<Integer> optionalInt(String name, Integer min, Integer max) {
    Object v = args.get(name);
    if (v == null) return Optional.empty();
    if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()))
      throw new Invalid(
          tool, "Invalid input: expected number, received " + typeName(v) + " at " + name);
    long value = n.longValue();
    if (min != null && value < min)
      throw new Invalid(tool, "Too small: expected number to be >=" + min + " at " + name);
    if (max != null && value > max)
      throw new Invalid(tool, "Too big: expected number to be <=" + max + " at " + name);
    return Optional.of((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)));
  }

  private static String typeName(Object v) {
    if (v instanceof String) return "string";
    if (v instanceof Number) return "number";
    if (v instanceof Boolean) return "boolean";
    if (v instanceof Map) return "object";
    return "array";
  }
}
