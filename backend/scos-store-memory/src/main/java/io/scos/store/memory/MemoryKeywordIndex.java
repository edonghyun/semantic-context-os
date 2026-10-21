package io.scos.store.memory;

import io.scos.engine.support.text.Text;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 메모리 참조 구현 — TS SqliteLexicalIndex의 세 경로를 bm25 없이 단순화한 수식: 3글자 이상 토큰은 정확 부분 문자열 0.5(+등장 비율), 공백 제거
 * 본문에만 있으면 0.25(compact), 3글자 미만 토큰은 count/(1+count). 계약의 순서 성질을 만족하는 **기준 구현**이지 제품 수식이 아니다.
 */
public final class MemoryKeywordIndex implements KeywordIndex {
  private final Map<SpaceId, LinkedHashMap<String, IndexedText>> spaces = new LinkedHashMap<>();

  @Override
  public IndexCapabilities capabilities() {
    return IndexCapabilities.exactTransactional();
  }

  @Override
  public synchronized List<ScoredRef> search(
      SpaceId space, String text, int k, SearchFilter filter) {
    List<String> tokens = Text.splitWhitespace(text);
    List<ScoredRef> hits = new ArrayList<>();
    for (IndexedText u : spaces.getOrDefault(space, new LinkedHashMap<>()).values()) {
      if (!filter.accepts(u.partition(), u.archived())) continue;
      String hay = (u.title() + "\n" + u.content()).toLowerCase(Locale.ROOT);
      String compact = Text.stripWhitespace(hay);
      double score = 0;
      for (String t : tokens) {
        if (t.isEmpty()) continue;
        String needle = t.toLowerCase(Locale.ROOT);
        int c = count(hay, needle);
        if (needle.codePointCount(0, needle.length()) < 3) {
          score += c / (1.0 + c);
        } else if (c > 0) {
          score += 0.5 + 0.5 * (c / (1.0 + c));
        } else if (Text.trigrams(needle).stream().anyMatch(compact::contains)) {
          score += 0.25; // TS compact trigram-OR 등가(N-1)
        }
      }
      if (score > 0) hits.add(new ScoredRef(u.unitId(), u.documentId(), score));
    }
    hits.sort((a, b) -> Double.compare(b.score(), a.score())); // 안정 정렬 → 동점은 적재 순
    return hits.size() > k ? List.copyOf(hits.subList(0, k)) : List.copyOf(hits);
  }

  private static int count(String hay, String needle) {
    int c = 0, i = hay.indexOf(needle);
    while (i >= 0) {
      c++;
      i = hay.indexOf(needle, i + needle.length());
    }
    return c;
  }

  @Override
  public synchronized void upsert(SpaceId space, List<IndexedText> units) {
    var map = spaces.computeIfAbsent(space, s -> new LinkedHashMap<>());
    for (IndexedText u : units) map.put(u.unitId(), u);
  }

  @Override
  public synchronized void delete(SpaceId space, List<String> unitIds) {
    var map = spaces.get(space);
    if (map != null) unitIds.forEach(map::remove);
  }
}
