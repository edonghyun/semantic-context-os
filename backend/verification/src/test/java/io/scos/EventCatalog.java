package io.scos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** logging.md §9 이벤트 카탈로그 표와 코드의 {@code EventLog} 호출 대조. */
final class EventCatalog {
  /** 작업 디렉터리(backend) 기준 로깅 규약 문서. */
  static final Path DOCUMENT = Path.of("../docs/conventions/logging.md");

  static final Pattern SECTION = Pattern.compile("(?m)^## 9\\. .*$");

  static final Pattern NEXT_SECTION = Pattern.compile("(?m)^## ");

  /** 표의 행: 첫 칸이 백틱 이름, 둘째 칸이 수준(`info / debug`처럼 여럿일 수 있다). */
  static final Pattern ROW = Pattern.compile("(?m)^\\| `([^`]+)` \\| ([^|]+?) \\|");

  private EventCatalog() {}

  /** §9 표의 이벤트 이름 → 수준 집합(`info / debug`는 둘). */
  static Map<String, Set<String>> parse(String markdown) {
    Matcher section = SECTION.matcher(markdown);
    if (!section.find()) throw new IllegalStateException("given 준비 실패: 로깅 규약 문서에 §9 이벤트 카탈로그가 없다");
    Matcher next = NEXT_SECTION.matcher(markdown);
    int end = next.find(section.end()) ? next.start() : markdown.length();
    Map<String, Set<String>> catalog = new LinkedHashMap<>();
    Matcher row = ROW.matcher(markdown.substring(section.end(), end));
    while (row.find())
      catalog.put(
          row.group(1),
          Arrays.stream(row.group(2).split("/"))
              .map(String::strip)
              .collect(Collectors.toCollection(TreeSet::new)));
    return catalog;
  }

  /** 이름 차이: 코드에만 있는 이름(위치와 함께)과 카탈로그에만 있는 이름. 이름을 정할 수 없는 호출은 이름 규칙이 잡는다. */
  static List<String> nameDifferences(
      List<EventLogCalls.Call> calls, Map<String, Set<String>> catalog) {
    Map<String, List<String>> code = locations(calls);
    List<String> differences = new ArrayList<>();
    code.forEach(
        (name, where) -> {
          if (!catalog.containsKey(name))
            differences.add("카탈로그(§9)에 없다: " + name + " (" + String.join(", ", where) + ")");
        });
    catalog.keySet().stream()
        .filter(name -> !code.containsKey(name))
        .sorted()
        .forEach(name -> differences.add("코드에 없다: " + name));
    return differences;
  }

  /** 두 쪽에 다 있는 이름의 수준 차이. */
  static List<String> levelDifferences(
      List<EventLogCalls.Call> calls, Map<String, Set<String>> catalog) {
    Map<String, Set<String>> code = new TreeMap<>();
    calls.forEach(
        c -> c.name().ifPresent(n -> code.computeIfAbsent(n, k -> new TreeSet<>()).add(c.level())));
    List<String> differences = new ArrayList<>();
    code.forEach(
        (name, levels) -> {
          Set<String> documented = catalog.get(name);
          if (documented != null && !documented.equals(levels))
            differences.add(
                "수준이 다르다: " + name + " 코드 " + levels + " 카탈로그 " + new TreeSet<>(documented));
        });
    return differences;
  }

  /** 이름 → 호출 위치들(이름 순). */
  private static Map<String, List<String>> locations(List<EventLogCalls.Call> calls) {
    Map<String, List<String>> code = new TreeMap<>();
    calls.forEach(
        c ->
            c.name()
                .ifPresent(n -> code.computeIfAbsent(n, k -> new ArrayList<>()).add(c.where())));
    return code;
  }
}
