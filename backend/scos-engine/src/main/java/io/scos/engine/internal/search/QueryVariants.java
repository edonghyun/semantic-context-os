package io.scos.engine.internal.search;

import io.scos.engine.support.text.Text;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 변형 질의(TS QueryVariantsStage + ConjunctionQueryDecomposition): "A 그리고 B"·"A와 B"를 두 조각으로 나눈다. 조각이
 * 2토큰 미만이면 분해하지 않는다. 중복·원질의 제외·상한.
 */
final class QueryVariants {
  private static final Pattern EXPLICIT =
      Pattern.compile(Text.whitespaceClass() + "+(?:그리고|및)" + Text.whitespaceClass() + "+");
  private static final Pattern JOSA_TAIL = Pattern.compile("^(.{2,})(와|과)$");
  private static final int MIN_PART_TOKENS = 2;

  private final int maxVariants;

  QueryVariants(int maxVariants) {
    this.maxVariants = maxVariants;
  }

  List<String> of(String query) {
    List<String> parts = decompose(query);
    LinkedHashSet<String> unique = new LinkedHashSet<>(parts);
    List<String> out = new ArrayList<>();
    for (String v : unique) {
      if (v.isBlank() || v.equals(query)) continue;
      out.add(v);
      if (out.size() >= maxVariants) break;
    }
    return out;
  }

  private static List<String> decompose(String query) {
    String[] explicit = EXPLICIT.split(query, -1);
    List<String> parts = explicit.length > 1 ? List.of(explicit) : splitOnJosaTail(query);
    if (parts.size() < 2) return List.of();
    List<String> trimmed = parts.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
    for (String p : trimmed) if (Text.splitWhitespace(p).size() < MIN_PART_TOKENS) return List.of();
    return trimmed;
  }

  private static List<String> splitOnJosaTail(String query) {
    String[] tokens = Text.splitWhitespace(query).toArray(String[]::new);
    for (int i = 0; i < tokens.length - 1; i++) {
      Matcher m = JOSA_TAIL.matcher(tokens[i]);
      if (m.matches()) {
        List<String> head = new ArrayList<>(List.of(tokens).subList(0, i));
        head.add(m.group(1));
        return List.of(
            String.join(" ", head),
            String.join(" ", List.of(tokens).subList(i + 1, tokens.length)));
      }
    }
    return List.of(query);
  }
}
