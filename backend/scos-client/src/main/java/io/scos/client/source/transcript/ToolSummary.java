package io.scos.client.source.transcript;

import tools.jackson.databind.JsonNode;

/** 도구 호출 한 줄 요약: `- 도구 <이름>: <대상>`. 대상이 없으면 이름만 쓴다. */
final class ToolSummary {
  private ToolSummary() {}

  static String of(String name, JsonNode input) {
    String target = target(input);
    return target.isEmpty() ? "- 도구 " + name : "- 도구 " + name + ": " + target;
  }

  private static String target(JsonNode input) {
    if (!input.isObject()) return "";
    String filePath = input.path("file_path").asString("");
    if (!filePath.isEmpty()) return filePath;
    String command = input.path("command").asString("");
    if (!command.isEmpty()) return clip(firstLine(command), 80);
    String pattern = input.path("pattern").asString("");
    if (!pattern.isEmpty()) return pattern;
    return input.path("url").asString("");
  }

  static String firstLine(String text) {
    int end = text.indexOf('\n');
    return end < 0 ? text : text.substring(0, end);
  }

  /** 코드포인트 단위로 앞 {@code max}자. */
  static String clip(String text, int max) {
    if (text.codePointCount(0, text.length()) <= max) return text;
    return text.substring(0, text.offsetByCodePoints(0, max));
  }
}
