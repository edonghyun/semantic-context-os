package io.scos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 메서드 길이 고정 목록(ratchet): 줄마다 "본문 코드 줄 수 TAB 열쇠", `#`로 시작하는 줄은 주석. 목록 밖 메서드는 상한을 넘으면 안 되고, 목록의 메서드는
 * 고정된 줄 수보다 길어지면 안 된다. 줄어들거나 사라진 메서드는 목록 갱신을 요구한다 — 고정 수가 실제와 같아야 다시 늘지 못한다.
 */
final class MethodLengthBaseline {
  static final String HEADER =
      """
      # 메서드 길이 고정 목록(TESTING §13.2): 상한을 넘는 기존 제품 main 메서드와 본문 코드 줄 수. 줄을 더하거나 수를 늘리지 않는다.
      # 갱신(줄어든 것만 낮추거나 지운다): ./gradlew :verification:fastTest --tests io.scos.MethodLengthRulesTest -Pscos.freeze.update=true
      """;

  private final SortedMap<String, Integer> lengths;

  private MethodLengthBaseline(SortedMap<String, Integer> lengths) {
    this.lengths = lengths;
  }

  static MethodLengthBaseline parse(String text) {
    SortedMap<String, Integer> lengths = new TreeMap<>();
    for (String line : text.lines().toList()) {
      if (line.isBlank() || line.startsWith("#")) continue;
      int tab = line.indexOf('\t');
      lengths.put(line.substring(tab + 1), Integer.parseInt(line.substring(0, tab)));
    }
    return new MethodLengthBaseline(lengths);
  }

  /** 현재 길이(열쇠 → 줄 수)가 목록과 어긋나는 것. 비어 있으면 통과다. */
  List<String> problems(Map<String, Integer> current, int limit) {
    List<String> problems = new ArrayList<>();
    new TreeMap<>(current)
        .forEach(
            (key, length) -> {
              Integer frozen = lengths.get(key);
              if (frozen == null && length > limit)
                problems.add("새로 " + limit + "줄을 넘는다(" + length + "줄): " + key);
              else if (frozen != null && length > frozen)
                problems.add("고정 " + frozen + "줄보다 길어졌다(" + length + "줄): " + key);
            });
    lengths.forEach(
        (key, frozen) -> {
          Integer length = current.get(key);
          if (length == null) problems.add("사라졌다 — 목록 갱신: " + key);
          else if (length < frozen)
            problems.add("줄었다(" + frozen + " → " + length + "줄) — 목록 갱신: " + key);
        });
    return problems;
  }

  /** 줄어든 것만 낮추고 상한 안으로 들어왔거나 사라진 것은 지운 목록. 새로 넘는 것·길어진 것은 넣지 않는다(그대로 실패한다). */
  MethodLengthBaseline updated(Map<String, Integer> current, int limit) {
    SortedMap<String, Integer> kept = new TreeMap<>();
    lengths.forEach(
        (key, frozen) -> {
          Integer length = current.get(key);
          if (length != null && length > limit) kept.put(key, Math.min(frozen, length));
        });
    return new MethodLengthBaseline(kept);
  }

  /** 목록 파일 내용(머리 주석 + 열쇠 순). */
  String format() {
    StringBuilder out = new StringBuilder(HEADER);
    lengths.forEach((key, length) -> out.append(length).append('\t').append(key).append('\n'));
    return out.toString();
  }
}
