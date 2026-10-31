package io.scos.client.source.transcript;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 줄 번호 순 트랜스크립트 줄을 턴 묶음으로 쌓는다. 묶음 = 직전 사용자 발화 뒤의 어시스턴트 텍스트·도구 요약 + 이번 사용자 발화. 사용자 발화 없이 끝난 꼬리도 한
 * 묶음이다.
 */
final class TurnBundles {
  private static final String ASK = "AskUserQuestion";
  private static final List<String> COMMAND_PREFIXES =
      List.of(
          "<command-name>",
          "<command-message>",
          "<command-args>",
          "<local-command-stdout>",
          "<bash-input>",
          "<bash-stdout>",
          "<bash-stderr>");
  private static final Pattern SUMMARY =
      Pattern.compile("<summary>(.*?)</summary>", Pattern.DOTALL);

  private final List<String> sections = new ArrayList<>();
  private final List<String> pending = new ArrayList<>();
  private final Map<String, List<String>> askedQuestions = new HashMap<>();
  private String firstUtterance = "";
  private String lastTimestamp = "";
  private int turn;

  /** 줄 하나를 반영한다. 투영 대상이 아닌 줄은 무시한다. */
  void accept(JsonNode line) {
    String timestamp = line.path("timestamp").asString("");
    if (!timestamp.isEmpty()) lastTimestamp = timestamp;
    switch (line.path("type").asString("")) {
      case "assistant" -> assistant(line);
      case "user" -> user(line, timestamp);
      default -> {}
    }
  }

  /** 남은 꼬리를 닫고 본문 구역들을 낸다. */
  List<String> finish() {
    if (!pending.isEmpty()) close(lastTimestamp, "");
    return sections;
  }

  String firstUtterance() {
    return firstUtterance;
  }

  private void assistant(JsonNode line) {
    JsonNode content = line.path("message").path("content");
    if (content.isString()) {
      addText(content.asString());
      return;
    }
    if (!content.isArray()) return;
    for (JsonNode block : content) {
      switch (block.path("type").asString("")) {
        case "text" -> addText(block.path("text").asString(""));
        case "tool_use" -> toolUse(block);
        default -> {} // thinking 등은 넣지 않는다
      }
    }
  }

  private void addText(String text) {
    if (!text.isBlank()) pending.add(text.strip());
  }

  private void toolUse(JsonNode block) {
    String name = block.path("name").asString("");
    if (!name.equals(ASK)) {
      pending.add(ToolSummary.of(name, block.path("input")));
      return;
    }
    List<String> questions = new ArrayList<>();
    for (JsonNode q : block.path("input").path("questions")) {
      String text = q.path("question").asString("");
      if (!text.isEmpty()) questions.add(text);
    }
    askedQuestions.put(block.path("id").asString(""), questions);
  }

  private void user(JsonNode line, String timestamp) {
    JsonNode answers = line.path("toolUseResult").path("answers");
    if (answers.isObject()) {
      close(timestamp, answerText(line, answers));
      return;
    }
    if (line.path("isMeta").asBoolean(false) || line.path("isCompactSummary").asBoolean(false))
      return;
    String text = userText(line.path("message").path("content"));
    if (text.isBlank()) return;
    String trimmed = text.stripLeading();
    if (trimmed.startsWith("<task-notification>")) {
      pending.add("- 알림: " + ToolSummary.clip(summaryOf(trimmed), 120));
    } else if (COMMAND_PREFIXES.stream().anyMatch(trimmed::startsWith)) {
      pending.add("- 명령: " + ToolSummary.clip(ToolSummary.firstLine(trimmed), 80));
    } else {
      close(timestamp, "사용자: " + text.strip());
      if (firstUtterance.isEmpty()) firstUtterance = text.strip();
    }
  }

  /** 질문 도구의 답: 호출의 질문 순서대로 `질문: …\n답: …`, 호출을 못 찾으면 답 항목 순서대로. */
  private String answerText(JsonNode line, JsonNode answers) {
    List<String> questions = new ArrayList<>();
    for (JsonNode block : line.path("message").path("content")) {
      questions.addAll(
          askedQuestions.getOrDefault(block.path("tool_use_id").asString(""), List.of()));
    }
    if (questions.isEmpty()) answers.properties().forEach(e -> questions.add(e.getKey()));
    List<String> out = new ArrayList<>();
    for (String question : questions) {
      JsonNode answer = answers.get(question);
      if (answer != null) out.add("질문: " + question + "\n답: " + answerValue(answer));
    }
    return String.join("\n", out);
  }

  private static String answerValue(JsonNode answer) {
    if (!answer.isArray()) return answer.asString("");
    List<String> parts = new ArrayList<>();
    for (JsonNode part : answer) parts.add(part.asString(""));
    return String.join(", ", parts);
  }

  private void close(String timestamp, String utterance) {
    turn++;
    StringBuilder section =
        new StringBuilder("## 턴 ").append(turn).append(" · ").append(seconds(timestamp));
    for (String part : pending) section.append("\n\n").append(part);
    if (!utterance.isEmpty()) section.append("\n\n").append(utterance);
    sections.add(section.toString());
    pending.clear();
    if (firstUtterance.isEmpty() && utterance.startsWith("질문: ")) firstUtterance = utterance;
  }

  private static String summaryOf(String notification) {
    Matcher m = SUMMARY.matcher(notification);
    return m.find() ? m.group(1).strip() : "";
  }

  private static String userText(JsonNode content) {
    if (content.isString()) return content.asString();
    if (!content.isArray()) return "";
    List<String> parts = new ArrayList<>();
    for (JsonNode block : content) {
      if (block.path("type").asString("").equals("text"))
        parts.add(block.path("text").asString(""));
    }
    return String.join("\n", parts);
  }

  /** 초 단위 ISO UTC. 파싱할 수 없으면 원문 그대로. */
  private static String seconds(String timestamp) {
    try {
      return Instant.parse(timestamp).truncatedTo(ChronoUnit.SECONDS).toString();
    } catch (DateTimeParseException e) {
      return timestamp;
    }
  }
}
