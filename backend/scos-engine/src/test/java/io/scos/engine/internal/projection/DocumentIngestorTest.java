package io.scos.engine.internal.projection;

import static io.scos.engine.internal.projection.IngestSamples.BODY_A;
import static io.scos.engine.internal.projection.IngestSamples.BODY_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.engine.support.projection.DeadLetter;
import io.scos.engine.support.projection.EmbeddingCounts;
import io.scos.engine.support.projection.SyncReport;
import io.scos.engine.support.projection.WriteGuardException;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import io.scos.store.memory.MemoryEmbeddingCache;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemorySpaceMeta;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * G5·G6·G9: 멱등(중복 유닛 0)·바뀐 유닛만 재임베딩·수명주기 보존·prune·축소 삭제·임베딩 실패 문서의 DeadLetter 기록·재투영 실패 시 기존 유닛·벡터
 * 보존·세대 백필. 메모리 어댑터로 검증한다.
 */
@DisplayName("문서 투영은 멱등이고 바뀐 유닛만 다시 임베딩한다(G5·G6·G9)")
class DocumentIngestorTest {
  static final SpaceId S = new SpaceId("legacy");
  static final String SRC = "slack-synthetic";

  MemoryUnitStore units = new MemoryUnitStore();
  MemoryKeywordIndex keywords = new MemoryKeywordIndex();
  MemoryVectorIndex vectors = new MemoryVectorIndex();
  MemorySpaceMeta meta = new MemorySpaceMeta();
  RecordingEmbedder embedder = new RecordingEmbedder();
  MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  DocumentIngestor ingestor =
      new DocumentIngestor(
          TransactionOperations.withoutTransaction(),
          units,
          keywords,
          vectors,
          meta,
          embedder,
          new IngestPipeline(new JtokkitTokenizer()),
          clock);

  static SourceDocument doc(String id, String body, String updatedAt) {
    return new SourceDocument(
        SRC,
        id,
        "slack://" + SRC + "/" + id,
        "제목 " + id,
        body,
        "2026-10-01T00:00:00.000Z",
        updatedAt);
  }

  static final String DAY1 = "2026-10-13T00:00:00.000Z";
  static final String DAY2 = "2026-10-14T00:00:00.000Z";
  static final String DAY3 = "2026-10-15T00:00:00.000Z";
  static final String GENERATION = "GeminiEmbedder:4:title+aliases+content";

  /** 유닛 2개짜리 a와 1개짜리 b. */
  static final List<SourceDocument> TWO_DOCS =
      List.of(doc("a", BODY_A, DAY1), doc("b", BODY_B, DAY1));

  /** 주어진 문서만 현존하는 것으로 투영한다. */
  SyncReport sync(List<SourceDocument> docs) {
    Set<String> present = docs.stream().map(SourceDocument::documentId).collect(Collectors.toSet());
    return ingestor.sync(S, SRC, docs, present);
  }

  SyncReport sync(SourceDocument doc) {
    return sync(List.of(doc));
  }

  List<String> unitIds() {
    return units.listAll(S).stream().map(ContextUnit::id).toList();
  }

  // ---- F9 임베딩 캐시(ADR-0026) ----

  static final double[] CACHED_VECTOR = {9, 8, 7, 6};
  static final double[] SHORT_VECTOR = {9, 8, 7};
  static final String OTHER_GENERATION = "GeminiEmbedder:768:title+aliases+content";

  MemoryEmbeddingCache cache = new MemoryEmbeddingCache();
  DocumentIngestor cachedIngestor =
      new DocumentIngestor(
          TransactionOperations.withoutTransaction(),
          units,
          keywords,
          vectors,
          meta,
          embedder,
          cache,
          new IngestPipeline(new JtokkitTokenizer()),
          clock);

  /** 이 문서들을 캐시 없이 처음 투영할 때 임베더가 받는 입력(빈 저장소에 한 번 투영해 본다). */
  List<String> embeddingInputs(List<SourceDocument> docs) {
    RecordingEmbedder probe = new RecordingEmbedder();
    new DocumentIngestor(
            TransactionOperations.withoutTransaction(),
            new MemoryUnitStore(),
            new MemoryKeywordIndex(),
            new MemoryVectorIndex(),
            new MemorySpaceMeta(),
            probe,
            new IngestPipeline(new JtokkitTokenizer()),
            clock)
        .sync(
            S,
            SRC,
            docs,
            docs.stream().map(SourceDocument::documentId).collect(Collectors.toSet()));
    return List.copyOf(probe.calls);
  }

  void givenCached(String generation, List<String> inputs, double[] vector) {
    cache.putIfAbsent(
        generation,
        inputs.stream()
            .collect(Collectors.toMap(CachedEmbeddings::keyOf, i -> vector, (a, b) -> a)));
  }

  /** 그 문서의 유닛 → 색인된 벡터. */
  Map<ContextUnit, double[]> indexedVectorsOf(String documentId) {
    Map<String, double[]> byId = vectors.vectorsOf(S, unitIds());
    Map<ContextUnit, double[]> out = new LinkedHashMap<>();
    for (ContextUnit u : units.listAll(S))
      if (u.documentId().equals(documentId)) out.put(u, byId.get(u.id()));
    return out;
  }

  /** 임베더가 이 유닛의 입력에 돌려주는 벡터. */
  static double[] ownVector(ContextUnit u) {
    return RecordingEmbedder.vectorOf(DocumentIngestor.embeddingInputOf(u));
  }

  SyncReport syncWithCache(List<SourceDocument> docs) {
    return cachedIngestor.sync(
        S, SRC, docs, docs.stream().map(SourceDocument::documentId).collect(Collectors.toSet()));
  }

  @Nested
  @DisplayName("임베딩 캐시를 두고 투영하면")
  class WithCache {
    @Test
    @DisplayName("캐시에 있는 입력은 임베더에 보내지 않는다")
    void cachedInputsSkipEmbedder() {
      // given
      givenCached(GENERATION, embeddingInputs(TWO_DOCS), CACHED_VECTOR);

      // when
      syncWithCache(TWO_DOCS);

      // then
      assertThat(embedder.calls).isEmpty();
    }

    @Test
    @DisplayName("캐시 벡터를 유닛 벡터로 색인한다")
    void indexesCachedVectors() {
      // given
      givenCached(GENERATION, embeddingInputs(TWO_DOCS), CACHED_VECTOR);

      // when
      syncWithCache(TWO_DOCS);

      // then
      assertThat(vectors.vectorsOf(S, unitIds()).values())
          .hasSize(3)
          .allSatisfy(v -> assertThat(v).containsExactly(CACHED_VECTOR));
    }

    @Test
    @DisplayName("캐시에 없는 유닛은 자기 입력의 임베더 벡터로 색인한다")
    void indexesOwnVectorForMisses() {
      // given: b만 캐시에 있다
      givenCached(GENERATION, embeddingInputs(List.of(TWO_DOCS.get(1))), CACHED_VECTOR);

      // when
      syncWithCache(TWO_DOCS);

      // then
      assertThat(indexedVectorsOf("a"))
          .as("캐시에 없는 문서 a의 유닛 둘")
          .hasSize(2)
          .allSatisfy((u, v) -> assertThat(v).containsExactly(ownVector(u)));
    }

    @Test
    @DisplayName("캐시에 없는 입력만 임베더에 보낸다")
    void embedsOnlyMisses() {
      // given: a만 캐시에 있다
      givenCached(GENERATION, embeddingInputs(List.of(TWO_DOCS.get(0))), CACHED_VECTOR);

      // when
      syncWithCache(TWO_DOCS);

      // then
      assertThat(embedder.calls)
          .containsExactlyElementsOf(embeddingInputs(List.of(TWO_DOCS.get(1))));
    }

    @Test
    @DisplayName("보고는 캐시에서 얻은 입력과 임베딩한 입력을 센다")
    void reportsCacheHits() {
      // given: a(유닛 2)만 캐시에 있다
      givenCached(GENERATION, embeddingInputs(List.of(TWO_DOCS.get(0))), CACHED_VECTOR);

      // when
      SyncReport report = syncWithCache(TWO_DOCS);

      // then
      assertThat(report.embeddings()).isEqualTo(new EmbeddingCounts(2, 1));
    }

    @Test
    @DisplayName("다른 세대의 캐시 벡터는 쓰지 않는다")
    void ignoresOtherGeneration() {
      // given
      givenCached(OTHER_GENERATION, embeddingInputs(TWO_DOCS), CACHED_VECTOR);

      // when
      syncWithCache(TWO_DOCS);

      // then
      assertThat(embedder.calls).containsExactlyElementsOf(embeddingInputs(TWO_DOCS));
    }

    @Test
    @DisplayName("차원이 다른 캐시 벡터는 쓰지 않는다")
    void ignoresOtherDimension() {
      // given
      givenCached(GENERATION, embeddingInputs(TWO_DOCS), SHORT_VECTOR);

      // when
      syncWithCache(TWO_DOCS);

      // then
      assertThat(embedder.calls).containsExactlyElementsOf(embeddingInputs(TWO_DOCS));
    }
  }

  @Nested
  @DisplayName("빈 저장소에 a(유닛 2)·b(유닛 1)를 처음 투영하면")
  class FirstSync {
    @Test
    @DisplayName("문서 수와 유닛 수를 오류 없이 보고한다")
    void reportsCounts() {
      // when
      SyncReport report = sync(TWO_DOCS);

      // then
      assertThat(report)
          .extracting(SyncReport::documents, SyncReport::units, SyncReport::errors)
          .containsExactly(2, 3, List.of());
    }

    @Test
    @DisplayName("유닛을 저장한다")
    void storesUnits() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(units.listAll(S)).hasSize(3);
    }

    @Test
    @DisplayName("유닛마다 한 번 임베딩한다")
    void embedsEachUnitOnce() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(embedder.calls).hasSize(3);
    }

    @Test
    @DisplayName("유닛마다 벡터를 색인한다")
    void indexesVectors() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(vectors.vectorsOf(S, unitIds())).hasSize(3);
    }

    @Test
    @DisplayName("유닛마다 자기 입력의 임베더 벡터를 색인한다")
    void indexesOwnVector() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(indexedVectorsOf("a"))
          .as("문서 a의 유닛 둘")
          .hasSize(2)
          .allSatisfy((u, v) -> assertThat(v).containsExactly(ownVector(u)));
    }

    @Test
    @DisplayName("본문을 키워드로 색인한다")
    void indexesKeywords() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(keywords.search(S, "커넥션 풀", 5, SearchFilter.NONE)).isNotEmpty();
    }

    @Test
    @DisplayName("임베딩 세대 메타를 기록한다")
    void recordsGeneration() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(meta.get(S, "embedding:version")).contains(GENERATION);
    }

    @Test
    @DisplayName("소스 커서를 투영 시각으로 기록한다")
    void recordsCursor() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(meta.get(S, "cursor:" + SRC)).contains("2026-10-14T00:00:00Z");
    }
  }

  @Nested
  @DisplayName("투영한 같은 문서를 다시 투영하면(G5)")
  class UnchangedResync {
    @BeforeEach
    void givenSynced() {
      sync(TWO_DOCS);
      embedder.forgetCalls();
    }

    @Test
    @DisplayName("임베딩을 호출하지 않는다")
    void embedsNothing() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(embedder.calls).isEmpty();
    }

    @Test
    @DisplayName("지운 것 없이 같은 유닛 수를 보고한다")
    void reportsSameUnits() {
      // when
      SyncReport report = sync(TWO_DOCS);

      // then
      assertThat(report).extracting(SyncReport::units, SyncReport::deleted).containsExactly(3, 0);
    }

    @Test
    @DisplayName("유닛을 중복 저장하지 않는다")
    void storesNoDuplicates() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(units.listAll(S)).hasSize(3);
    }
  }

  /** 첫 유닛을 archived + invalidatedAt으로 바꿔 수명주기 상태를 만든다(투영 밖에서 바뀐 상태의 보존을 보기 위해). */
  ContextUnit givenFirstUnitArchived() {
    ContextUnit first = units.listAll(S).get(0);
    units.upsert(
        S,
        List.of(
            ContextUnits.from(first).archived().invalidatedAt(DAY1).withoutEmbedding().build()));
    return first;
  }

  ContextUnit reload(ContextUnit unit) {
    return units.getMany(S, List.of(unit.id())).get(0);
  }

  @Nested
  @DisplayName("첫 유닛을 archived로 바꾼 a를 본문 그대로 capturedAt만 바꿔 투영하면")
  class RecapturedSameBody {
    ContextUnit first;

    @BeforeEach
    void givenFirstUnitArchivedAfterSync() {
      sync(doc("a", BODY_A, DAY1));
      first = givenFirstUnitArchived();
      embedder.forgetCalls();
    }

    @Test
    @DisplayName("저장된 capturedAt을 갱신한다")
    void updatesCapturedAt() {
      // when
      sync(doc("a", BODY_A, DAY2));

      // then
      assertThat(reload(first).provenance().capturedAt()).isEqualTo(DAY2);
    }

    @Test
    @DisplayName("tier와 invalidatedAt을 보존한다")
    void keepsLifecycle() {
      // when
      sync(doc("a", BODY_A, DAY2));

      // then
      ContextUnit unit = reload(first);
      assertThat(unit.tier()).isEqualTo(Tier.ARCHIVED);
      assertThat(unit.invalidatedAt()).contains(DAY1);
    }

    @Test
    @DisplayName("재임베딩하지 않는다")
    void embedsNothing() {
      // when
      sync(doc("a", BODY_A, DAY2));

      // then
      assertThat(embedder.calls).isEmpty();
    }
  }

  @Nested
  @DisplayName("첫 유닛을 archived로 바꾼 a를 그 유닛 본문만 바꿔 투영하면")
  class ChangedBody {
    static final String CHANGED_A = BODY_A.replace("커넥션 풀 고갈", "디스크 가득 참");

    ContextUnit first;

    @BeforeEach
    void givenFirstUnitArchivedAfterSync() {
      sync(doc("a", BODY_A, DAY1));
      first = givenFirstUnitArchived();
      embedder.forgetCalls();
    }

    @Test
    @DisplayName("그 유닛만 재임베딩한다")
    void reembedsOnlyThatUnit() {
      // when
      sync(doc("a", CHANGED_A, DAY3));

      // then
      assertThat(embedder.calls).singleElement().asString().contains("디스크 가득 참");
    }

    @Test
    @DisplayName("유닛을 늘리지 않는다")
    void keepsUnitCount() {
      // when
      sync(doc("a", CHANGED_A, DAY3));

      // then
      assertThat(units.listAll(S)).hasSize(2);
    }

    @Test
    @DisplayName("그 유닛은 raw로 돌아간다")
    void resetsTier() {
      // when
      sync(doc("a", CHANGED_A, DAY3));

      // then
      assertThat(reload(first).tier()).isEqualTo(Tier.RAW);
    }
  }

  @Nested
  @DisplayName("a를 투영한 뒤 본문을 바꾼 a의 재투영에서 임베더가 실패하면")
  class FailedReprojection {
    static final String CHANGED_A = BODY_A.replace("커넥션 풀 고갈", "디스크 가득 참");

    @BeforeEach
    void givenSyncedThenEmbedderFails() {
      if (!sync(doc("a", BODY_A, DAY1)).errors().isEmpty())
        throw new IllegalStateException("given 준비 실패: 첫 투영이 실패했다");
      embedder.failsWith429();
    }

    @Test
    @DisplayName("DeadLetter로 격리된다")
    void isolatesAsDeadLetter() {
      // when
      SyncReport report = sync(doc("a", CHANGED_A, DAY2));

      // then
      assertThat(report.errors())
          .singleElement()
          .extracting(DeadLetter::uri)
          .isEqualTo("slack://" + SRC + "/a");
    }

    @Test
    @DisplayName("기존 유닛이 옛 본문 그대로 남는다")
    void keepsPreviousUnits() {
      // when
      sync(doc("a", CHANGED_A, DAY2));

      // then
      assertThat(units.listAll(S))
          .hasSize(2)
          .extracting(ContextUnit::content)
          .anySatisfy(c -> assertThat(c).contains("커넥션 풀 고갈"))
          .noneSatisfy(c -> assertThat(c).contains("디스크 가득 참"));
    }

    @Test
    @DisplayName("기존 벡터가 남는다")
    void keepsPreviousVectors() {
      // when
      sync(doc("a", CHANGED_A, DAY2));

      // then
      assertThat(indexedVectorsOf("a"))
          .as("문서 a의 유닛 둘")
          .hasSize(2)
          .allSatisfy((u, v) -> assertThat(v).containsExactly(ownVector(u)));
    }

    @Test
    @DisplayName("옛 본문이 키워드 검색에 남는다")
    void keepsPreviousKeywords() {
      // when
      sync(doc("a", CHANGED_A, DAY2));

      // then
      assertThat(keywords.search(S, "커넥션 풀", 5, SearchFilter.NONE)).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("a·b를 투영한 뒤 a가 줄고 b가 현존 목록에서 빠지면")
  class ShrinkAndPrune {
    /** a의 "후속" 절을 뗀 본문(유닛 1). */
    static final String SHRUNK_A = BODY_A.substring(0, BODY_A.indexOf("## 후속"));

    @BeforeEach
    void givenSynced() {
      sync(TWO_DOCS);
    }

    @Test
    @DisplayName("줄어든 유닛과 빠진 문서의 유닛을 deleted로 센다")
    void countsDeleted() {
      // when
      SyncReport report = sync(doc("a", SHRUNK_A, DAY2));

      // then
      assertThat(report.deleted()).isEqualTo(2);
    }

    @Test
    @DisplayName("a의 남은 유닛 하나만 남긴다")
    void keepsRemainingUnit() {
      // when
      sync(doc("a", SHRUNK_A, DAY2));

      // then
      assertThat(units.listAll(S))
          .singleElement()
          .extracting(ContextUnit::documentId)
          .isEqualTo("a");
    }

    @Test
    @DisplayName("출처의 문서 목록에는 a만 남는다")
    void keepsOnlyRemainingDocument() {
      // when
      sync(doc("a", SHRUNK_A, DAY2));

      // then
      assertThat(units.listDocumentIds(S, SRC)).containsExactly("a");
    }

    @Test
    @DisplayName("빠진 문서를 키워드 색인에서도 지운다")
    void prunesKeywords() {
      // when
      sync(doc("a", SHRUNK_A, DAY2));

      // then
      assertThat(keywords.search(S, "온보딩 절차", 5, SearchFilter.NONE)).isEmpty();
    }

    @Test
    @DisplayName("남은 유닛의 벡터는 지우지 않는다")
    void keepsRemainingVector() {
      // when
      sync(doc("a", SHRUNK_A, DAY2));

      // then
      assertThat(vectors.vectorsOf(S, unitIds())).hasSize(1);
    }
  }

  @Nested
  @DisplayName("임베더가 429로 실패할 때 a를 투영하면")
  class EmbedderFailure {
    @BeforeEach
    void givenEmbedderFails() {
      embedder.failsWith429();
    }

    @Test
    @DisplayName("DeadLetter(stage=store)로 격리된다")
    void isolatesAsDeadLetter() {
      // when
      SyncReport report = sync(doc("a", BODY_A, DAY1));

      // then
      assertThat(report.errors())
          .singleElement()
          .extracting(DeadLetter::stage, DeadLetter::uri)
          .containsExactly("store", "slack://" + SRC + "/a");
    }

    @Test
    @DisplayName("투영한 문서로 세지 않는다")
    void countsNoDocument() {
      // when
      SyncReport report = sync(doc("a", BODY_A, DAY1));

      // then
      assertThat(report.documents()).isZero();
    }

    @Test
    @DisplayName("유닛을 남기지 않는다")
    void leavesNothing() {
      // when
      sync(doc("a", BODY_A, DAY1));

      // then
      assertThat(units.listAll(S)).isEmpty();
    }

    @Nested
    @DisplayName("실패한 뒤 임베더가 회복해 다시 투영하면")
    class AfterRecovery {
      @BeforeEach
      void givenFailedThenRecovered() {
        sync(doc("a", BODY_A, DAY1));
        embedder.recovers();
      }

      @Test
      @DisplayName("오류 없이 투영한다")
      void retriesCleanly() {
        // when
        SyncReport report = sync(doc("a", BODY_A, DAY1));

        // then
        assertThat(report.errors()).isEmpty();
      }

      @Test
      @DisplayName("유닛이 중복되지 않는다")
      void retryHasNoDuplicates() {
        // when
        sync(doc("a", BODY_A, DAY1));

        // then
        assertThat(unitIds()).hasSize(2).doesNotHaveDuplicates();
      }
    }
  }

  @Nested
  @DisplayName("임베더가 a의 입력에만 429로 실패할 때 a·b를 투영하면")
  class OneDocumentFails {
    @BeforeEach
    void givenEmbedderFailsOnlyForA() {
      embedder.failsWith429On("커넥션 풀 고갈");
    }

    @Test
    @DisplayName("a만 DeadLetter로 격리된다")
    void isolatesOnlyFailedDocument() {
      // when
      SyncReport report = sync(TWO_DOCS);

      // then
      assertThat(report.errors())
          .singleElement()
          .extracting(DeadLetter::uri)
          .isEqualTo("slack://" + SRC + "/a");
    }

    @Test
    @DisplayName("뒤의 b는 계속 투영해 유닛으로 남는다")
    void projectsOtherDocument() {
      // when
      sync(TWO_DOCS);

      // then
      assertThat(units.listAll(S))
          .singleElement()
          .extracting(ContextUnit::documentId)
          .isEqualTo("b");
    }

    @Test
    @DisplayName("투영한 문서 1건·유닛 1개로 보고한다")
    void countsOnlyProjectedDocument() {
      // when
      SyncReport report = sync(TWO_DOCS);

      // then
      assertThat(report).extracting(SyncReport::documents, SyncReport::units).containsExactly(1, 1);
    }
  }

  @Nested
  @DisplayName("a를 투영한 뒤 임베딩 세대 메타가 바뀐 상태로 다시 투영하면(G9)")
  class GenerationChange {
    @BeforeEach
    void givenOldGeneration() {
      sync(doc("a", BODY_A, DAY1));
      meta.set(S, "embedding:version", "HashingEmbedder:768:title+content");
      embedder.forgetCalls();
    }

    @Test
    @DisplayName("공간의 전 유닛을 재임베딩한다")
    void reembedsAll() {
      // when
      sync(doc("a", BODY_A, DAY1));

      // then
      assertThat(embedder.calls).hasSize(2);
    }

    @Test
    @DisplayName("세대 메타를 현재 세대로 갱신한다")
    void updatesGeneration() {
      // when
      sync(doc("a", BODY_A, DAY1));

      // then
      assertThat(meta.get(S, "embedding:version")).contains(GENERATION);
    }
  }

  @Nested
  @DisplayName("guard가 a의 저장을 거부할 때")
  class GuardRejects {
    /** backfill·prune이 guard를 부르지 않으니 첫 호출이 a의 persist 트랜잭션이다. */
    static final int PERSIST_CALL = 1;

    int guardCalls;

    @BeforeEach
    void givenCurrentGeneration() {
      meta.set(S, "embedding:version", GENERATION); // backfill이 guard를 부르지 않게
    }

    /** a를 현존 문서로(prune이 guard를 부르지 않게) 투영하고, a의 persist에서 guard가 rejection을 던진다. */
    SyncReport syncRejecting(RuntimeException rejection) {
      return ingestor.sync(
          S,
          SRC,
          List.of(doc("a", BODY_A, DAY1)),
          Set.of("a"),
          () -> {
            guardCalls++;
            if (guardCalls != PERSIST_CALL || embedder.calls.isEmpty())
              throw new IllegalStateException(
                  "given 준비 실패: a의 persist 전에 guard가 " + guardCalls + "번 불렸다");
            throw rejection;
          });
    }

    @Test
    @DisplayName("WriteGuardException은 격리 없이 그대로 전파된다")
    void propagatesWriteGuardException() {
      // given
      WriteGuardException rejection = new WriteGuardException("x");

      // when
      Throwable thrown = catchThrowable(() -> syncRejecting(rejection));

      // then
      assertThat(thrown).isSameAs(rejection);
    }

    @Test
    @DisplayName("WriteGuardException이면 a의 유닛을 저장하지 않는다")
    void storesNothingOnWriteGuardException() {
      // when
      var unused = catchThrowable(() -> syncRejecting(new WriteGuardException("x")));

      // then
      assertThat(units.listAll(S)).isEmpty();
    }

    @Test
    @DisplayName("다른 guard 예외는 a만 store 단계로 격리한다")
    void isolatesOtherGuardFailure() {
      // when
      SyncReport report = syncRejecting(new IllegalStateException("lease renew 실패"));

      // then
      assertThat(report.errors()).singleElement().extracting(DeadLetter::stage).isEqualTo("store");
    }
  }
}
