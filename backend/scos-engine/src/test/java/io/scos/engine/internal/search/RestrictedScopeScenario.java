package io.scos.engine.internal.search;

import static io.scos.engine.internal.search.SearchFixture.S;
import static io.scos.engine.internal.search.SearchSettingsBuilder.DEFAULT_PLAN;
import static io.scos.spi.store.ContextUnits.unit;

import io.scos.engine.DocumentNotFoundException;
import io.scos.engine.Freshness;
import io.scos.engine.internal.search.refine.LinkExpansion;
import io.scos.engine.internal.search.signal.RecencySignal;
import io.scos.engine.internal.search.signal.Scorer;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryUnitStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** 같은 코퍼스를 모든 읽기 출구에 연결한다. 메모리 포트는 SPI의 공통 계약 스위트를 통과한 구현을 쓴다. */
final class RestrictedScopeScenario {
  static final Principal ALICE = new Principal("alice");
  static final String DOCUMENT_A = "document-a";
  static final String QUERY = "정산";
  static final int LIMIT = 10;
  static final int AMPLE_BUDGET = 1000;
  static final int BEYOND_END = 100;
  static final String CAPTURED_AT = "2026-10-13T00:00:00Z";
  static final Clock NOW = Clock.fixed(Instant.parse(CAPTURED_AT), ZoneOffset.UTC);
  static final double[] MATCH_VECTOR = {1, 0};
  static final Scorer SCORER = new Scorer(List.of(new RecencySignal()));

  private final SearchFixture corpus = new SearchFixture(new MemoryAclStore());
  private final AccessScope access;
  private final ContextUnit parent;
  private final QuestionSearch search;

  RestrictedScopeScenario(AccessScope access) {
    this.access = access;
    parent =
        unit("a1")
            .inDocument(DOCUMENT_A)
            .content(QUERY)
            .linksTo("document-b")
            .capturedAt(CAPTURED_AT)
            .embedding(MATCH_VECTOR)
            .build();
    corpus.load(
        S,
        parent,
        unit("a2").inDocument(DOCUMENT_A).content(QUERY).capturedAt(CAPTURED_AT).build(),
        unit("a3").inDocument(DOCUMENT_A).content(QUERY).capturedAt(CAPTURED_AT).build(),
        unit("b1")
            .inDocument("document-b")
            .content(QUERY)
            .embedding(MATCH_VECTOR)
            .capturedAt(CAPTURED_AT)
            .build(),
        unit("c1")
            .inDocument("document-c")
            .content(QUERY)
            .compacted("a1")
            .capturedAt(CAPTURED_AT)
            .embedding(MATCH_VECTOR)
            .build());
    search =
        QuestionSearchBuilder.over(corpus)
            .embedder(FixedEmbedder.byKeyword(QUERY, MATCH_VECTOR, MATCH_VECTOR))
            .clock(NOW)
            .build();
  }

  QuestionSearch.Result search() {
    return search.search(access, S, QUERY, DEFAULT_PLAN, LIMIT);
  }

  ContextAssembler.Bundle assemble() {
    return new ContextAssembler(corpus.units, search, NOW, SCORER)
        .assemble(access, S, QUERY, AMPLE_BUDGET, DEFAULT_PLAN);
  }

  List<String> expandLinks() {
    return new LinkExpansion(corpus.units)
        .apply(access, S, List.of(new Candidate(parent, 1)), DEFAULT_PLAN).stream()
            .map(c -> c.unit().id())
            .toList();
  }

  DocumentReader.Page read() {
    return readAt(0);
  }

  DocumentReader.Page readBeyondEnd() {
    return readAt(BEYOND_END);
  }

  private DocumentReader.Page readAt(int offset) {
    return new DocumentReader(corpus.units).read(access, S, DOCUMENT_A, offset, LIMIT);
  }

  String missingMessage() {
    try {
      new DocumentReader(new MemoryUnitStore())
          .read(AccessScope.singleUser(), S, DOCUMENT_A, 0, LIMIT);
      throw new IllegalStateException("given 준비 실패: 빈 저장소에 문서가 있다");
    } catch (DocumentNotFoundException missing) {
      return missing.getMessage();
    }
  }

  List<RelatedDocuments.Related> related() {
    return new RelatedDocuments(corpus.units, corpus.vectors).find(access, S, DOCUMENT_A, LIMIT);
  }

  Freshness freshness() {
    return new FreshnessReader(corpus.maintenance, NOW).read(access, S);
  }
}
