package io.scos.store.memory;

import io.scos.engine.support.text.KeywordScore;
import io.scos.engine.support.text.Text;
import io.scos.spi.access.DocumentAccess;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.store.AccessScope;
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
import java.util.Optional;

/** 메모리 키워드 색인. SQLite와 같은 점수 정책을 쓰며 동점은 적재 순서로 유지한다. */
public final class MemoryKeywordIndex implements KeywordIndex {
  private final Map<SpaceId, LinkedHashMap<String, IndexedText>> spaces = new LinkedHashMap<>();

  private final MemoryAclStore acl;

  public MemoryKeywordIndex() {
    this(new MemoryAclStore());
  }

  public MemoryKeywordIndex(MemoryAclStore acl) {
    this.acl = acl;
  }

  @Override
  public IndexCapabilities capabilities() {
    return IndexCapabilities.exactTransactional();
  }

  @Override
  public synchronized List<ScoredRef> search(
      SpaceId space, String text, int k, SearchFilter filter) {
    var documents = spaces.getOrDefault(space, new LinkedHashMap<>()).values();
    DocumentAccess access = new DocumentAccess(filter.access());
    Map<String, DocumentAcl> acls =
        filter.access() instanceof AccessScope.Unrestricted
            ? Map.of()
            : acl.get(space, documents.stream().map(IndexedText::documentId).toList());
    KeywordScore scorer =
        KeywordScore.of(
            Text.splitWhitespace(text).stream().map(t -> t.toLowerCase(Locale.ROOT)).toList());
    List<ScoredRef> hits = new ArrayList<>();
    for (IndexedText u : documents) {
      if (!filter.accepts(u.partition(), u.archived())) continue;
      if (!access.admits(Optional.ofNullable(acls.get(u.documentId())))) continue;
      String hay = (u.title() + "\n" + u.content()).toLowerCase(Locale.ROOT);
      String compact = Text.stripWhitespace(hay);
      double score = scorer.score(hay, compact);
      if (score > 0) hits.add(new ScoredRef(u.unitId(), u.documentId(), score));
    }
    hits.sort((a, b) -> Double.compare(b.score(), a.score())); // 안정 정렬 → 동점은 적재 순
    return hits.size() > k ? List.copyOf(hits.subList(0, k)) : List.copyOf(hits);
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
