package io.scos.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.Principal;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.VectorIndex;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemorySpaceMeta;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.TestExecutors;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 공개 빌더로 키워드 전용 투영·검색을 조립하고 메모리 저장 결과를 관찰한다. */
final class KeywordOnlyScenario implements AutoCloseable {
  private static final SpaceId S = new SpaceId("legacy");
  private static final String SRC = "slack-synthetic";
  private static final String BODY = "# 정산 배치\n\n정산 배치는 매일 새벽에 돌고 실패하면 담당자에게 알림이 간다.\n";

  /** 검색 호출 횟수를 세는 벡터 색인. */
  static final class CountingVectors implements VectorIndex {
    final MemoryVectorIndex inner = new MemoryVectorIndex();
    int searches;
    int upserts;

    @Override
    public IndexCapabilities capabilities() {
      return inner.capabilities();
    }

    @Override
    public List<List<ScoredRef>> search(
        SpaceId space, List<double[]> queries, int k, SearchFilter filter) {
      searches++;
      return inner.search(space, queries, k, filter);
    }

    @Override
    public void upsert(SpaceId space, List<IndexedUnit> units) {
      upserts++;
      inner.upsert(space, units);
    }

    @Override
    public void delete(SpaceId space, List<String> unitIds) {
      inner.delete(space, unitIds);
    }

    @Override
    public Map<String, double[]> vectorsOf(
        SpaceId space, AccessScope access, List<String> unitIds) {
      return inner.vectorsOf(space, access, unitIds);
    }
  }

  /** 색인 쓰기 호출 수를 세는 키워드 색인. 유닛이 바뀔 때만 쓰기가 일어난다. */
  static final class CountingKeywords implements KeywordIndex {
    final MemoryKeywordIndex inner = new MemoryKeywordIndex();
    int upserts;

    @Override
    public IndexCapabilities capabilities() {
      return inner.capabilities();
    }

    @Override
    public List<ScoredRef> search(SpaceId space, String text, int k, SearchFilter filter) {
      return inner.search(space, text, k, filter);
    }

    @Override
    public void upsert(SpaceId space, List<IndexedText> units) {
      upserts++;
      inner.upsert(space, units);
    }

    @Override
    public void delete(SpaceId space, List<String> unitIds) {
      inner.delete(space, unitIds);
    }
  }

  private final MemoryUnitStore units = new MemoryUnitStore();
  private final CountingKeywords keywords = new CountingKeywords();
  private final CountingVectors vectors = new CountingVectors();
  private final MemoryUnitMaintenance maintenance = new MemoryUnitMaintenance(units, vectors.inner);

  private final MemorySpaceMeta meta = new MemorySpaceMeta();

  final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  ContextIngestorBuilder ingestorBuilder() {
    return new ContextIngestorBuilder()
        .maintenance(maintenance)
        .keywords(keywords)
        .vectors(vectors)
        .meta(meta)
        .clock(new MutableClock(Instant.parse("2026-10-14T00:00:00Z")));
  }

  ContextEngineBuilder engineBuilder() {
    return new ContextEngineBuilder()
        .units(units)
        .unrestricted(maintenance)
        .keywords(keywords)
        .vectors(vectors)
        .meterRegistry(registry)
        .lexicalExecutor(TestExecutors.CALLER_THREAD);
  }

  IngestReport ingest() {
    return ingestWith(ingestorBuilder().keywordOnly().build());
  }

  IngestReport ingestWith(ContextIngestor ingestor) {
    return ingestWith(ingestor, Set.of("d1"));
  }

  IngestReport ingest(Set<String> live) {
    return ingestWith(ingestorBuilder().keywordOnly().build(), live);
  }

  private IngestReport ingestWith(ContextIngestor ingestor, Set<String> live) {
    return ingestor.ingest(S, SRC, List.of(document()), live);
  }

  private SourceDocument document() {
    return new SourceDocument(
        SRC,
        "d1",
        "slack://" + SRC + "/d1",
        "정산 문서",
        BODY,
        "2026-10-01T00:00:00.000Z",
        "2026-10-13T00:00:00.000Z");
  }

  void givenDocumentIngested() {
    ingest();
    requireDocumentStored();
  }

  private void requireDocumentStored() {
    if (!maintenance.listAll(S).stream()
        .map(u -> u.provenance().documentId())
        .toList()
        .equals(List.of("d1")))
      throw new IllegalStateException("given 준비 실패: 문서 d1의 유닛 하나가 저장돼 있어야 한다");
  }

  SearchResult search() {
    return searchWith(engineBuilder().keywordOnly().build());
  }

  SearchResult searchWith(ContextEngine engine) {
    return engine.search(Principal.singleUser(), SearchQuery.of("정산"));
  }

  Map<String, double[]> storedVectors() {
    requireDocumentStored();
    return maintenance.storedVectors(S, maintenance.listAll(S).stream().map(u -> u.id()).toList());
  }

  int vectorSearches() {
    return vectors.searches;
  }

  int vectorUpserts() {
    requireDocumentStored();
    return vectors.upserts;
  }

  int keywordUpserts() {
    return keywords.upserts;
  }

  int storedUnits() {
    return maintenance.listAll(S).size();
  }

  List<String> documentIds() {
    return maintenance.listDocumentIds(S, SRC);
  }

  ContextIngestorBuilder withoutIngestorPort(String missing) {
    ContextIngestorBuilder builder = new ContextIngestorBuilder();
    if (!missing.equals("maintenance")) builder.maintenance(maintenance);
    if (!missing.equals("keywords")) builder.keywords(keywords);
    if (!missing.equals("vectors")) builder.vectors(vectors);
    if (!missing.equals("meta")) builder.meta(meta);
    if (!missing.equals("embedder")) builder.keywordOnly();
    return builder;
  }

  @Override
  public void close() {
    registry.close();
  }

  Optional<String> generation() {
    return meta.get(S, "embedding:version");
  }
}
