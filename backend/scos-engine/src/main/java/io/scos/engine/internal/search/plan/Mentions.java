package io.scos.engine.internal.search.plan;

import io.scos.engine.RankingSettings;
import io.scos.engine.support.text.Text;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 질의가 별명으로 말한 원천(CONTRACTS B13). 별명은 질의(NFKC·소문자)에서 낱말 시작 위치에 나와야 한다 — 앞 글자가 글자·숫자가 아니면 낱말 시작이고,
 * 뒤에는 조사가 붙어도 된다.
 *
 * @param indexed 말한 색인 원천 id
 * @param onlyNotIndexed 색인에 없는 원천만 말했다(검색 결과 0건)
 */
public record Mentions(Set<String> indexed, boolean onlyNotIndexed) {
  public static final Mentions NONE = new Mentions(Set.of(), false);

  public Mentions {
    indexed = Set.copyOf(indexed);
  }

  public static Mentions of(RankingSettings.Sources sources, String query) {
    if (sources.aliases().isEmpty() && sources.notIndexed().isEmpty()) return NONE;
    String text = Text.nfkcLower(query);
    Set<String> named =
        sources.aliases().entrySet().stream()
            .filter(e -> e.getValue().stream().anyMatch(alias -> startsWord(text, alias)))
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    boolean other =
        named.isEmpty() && sources.notIndexed().stream().anyMatch(alias -> startsWord(text, alias));
    return new Mentions(named, other);
  }

  static boolean startsWord(String text, String alias) {
    for (int at = text.indexOf(alias); at >= 0; at = text.indexOf(alias, at + 1))
      if (at == 0 || !Character.isLetterOrDigit(text.codePointBefore(at))) return true;
    return false;
  }
}
