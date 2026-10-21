package io.scos.engine.internal.projection;

import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.engine.support.projection.DeadLetter;
import io.scos.engine.support.projection.EmbeddingCounts;
import io.scos.engine.support.projection.SourceSync;
import io.scos.engine.support.projection.SyncReport;
import io.scos.engine.support.projection.WriteGuardException;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 문서 인제스트(TS core/ingestion/document-ingestor.ts, G5·G6·G9): 문서마다 파이프라인 → 기존 유닛과 대조해 바뀐 것만 저장·재임베딩
 * → 문서의 나머지 유닛 삭제(keepIds), 끝에 prune(현존 목록 밖 문서 삭제). 유닛 id가 결정론이라 재시도·재투영에 중복 유닛이 생기지 않는다(불변식 4).
 * 문서 단위 실패는 DeadLetter로 격리하고 다른 문서는 계속한다. 한 문서의 저장(유닛·키워드·벡터·삭제)은 한 트랜잭션이다(어댑터 조합의 성질,
 * DESIGN-PATTERNS §10).
 */
public final class DocumentIngestor implements SourceSync {
  /** sync 하나의 임베딩 셈. */
  private static final class EmbeddingTally {
    private int fromCache;
    private int embedded;

    EmbeddingCounts counts() {
      return new EmbeddingCounts(fromCache, embedded);
    }
  }

  static final int EMBED_BATCH = 100;

  private final TransactionOperations tx;
  private final UnitStore units;
  private final KeywordIndex keywords;
  private final VectorIndex vectors;
  private final SpaceMeta meta;
  private final Embedder embedder;
  private final CachedEmbeddings embeddings;
  private final IngestPipeline pipeline;
  private final Clock clock;

  /** 임베딩 캐시 없이(모든 입력을 임베더로). */
  public DocumentIngestor(
      TransactionOperations tx,
      UnitStore units,
      KeywordIndex keywords,
      VectorIndex vectors,
      SpaceMeta meta,
      Embedder embedder,
      IngestPipeline pipeline,
      Clock clock) {
    this(tx, units, keywords, vectors, meta, embedder, EmbeddingCache.NONE, pipeline, clock);
  }

  /**
   * @param cache 임베딩 캐시(F9, ADR-0026) — 같은 세대·같은 입력의 벡터가 있으면 그 입력은 임베더에 보내지 않는다
   */
  public DocumentIngestor(
      TransactionOperations tx,
      UnitStore units,
      KeywordIndex keywords,
      VectorIndex vectors,
      SpaceMeta meta,
      Embedder embedder,
      EmbeddingCache cache,
      IngestPipeline pipeline,
      Clock clock) {
    this.tx = tx;
    this.units = units;
    this.keywords = keywords;
    this.vectors = vectors;
    this.meta = meta;
    this.embedder = embedder;
    this.embeddings = new CachedEmbeddings(embedder, cache, embeddingGeneration(embedder.dim()));
    this.pipeline = pipeline;
    this.clock = clock;
  }

  /** G9: TS 결과 문자열을 리터럴로 쓴다(사용자 결정 2026-10-13, ADR-0004) — 카세트 임베더도 Gemini의 대역이다. */
  public String embeddingGeneration() {
    return embeddingGeneration(embedder.dim());
  }

  /** 이 차원의 임베딩 세대(G9). 임베딩 캐시의 키 공간이기도 하다(ADR-0026). */
  public static String embeddingGeneration(int dim) {
    return "GeminiEmbedder:" + dim + ":title+aliases+content";
  }

  /** TS embeddingInputOf: aliases 없으면 `title\ncontent`(기존 임베딩과 바이트 동일). */
  public static String embeddingInputOf(ContextUnit u) {
    return u.aliases().isEmpty()
        ? u.title() + "\n" + u.content()
        : u.title() + "\n" + String.join("\n", u.aliases()) + "\n" + u.content();
  }

  public SyncReport sync(
      SpaceId space, String sourceId, List<SourceDocument> documents, Set<String> liveDocumentIds) {
    return sync(space, sourceId, documents, liveDocumentIds, () -> {});
  }

  /**
   * guard는 쓰기 트랜잭션마다 그 안에서 먼저 실행된다 — 투영 점유 검사(저장 경계 fencing, 리뷰 B-I-6). 던지면 그 트랜잭션은 롤백된다. {@link
   * WriteGuardException}은 문서 격리 없이 전파되고, 다른 예외는 그 문서만 격리한다.
   */
  @Override
  public SyncReport sync(
      SpaceId space,
      String sourceId,
      List<SourceDocument> documents,
      Set<String> liveDocumentIds,
      Runnable guard) {
    String startedAt =
        DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    EmbeddingTally tally = new EmbeddingTally();
    backfillEmbeddings(space, guard, tally);
    int docs = 0, unitCount = 0, deleted = 0;
    List<DeadLetter> errors = new ArrayList<>();
    for (SourceDocument doc : documents) {
      String stage = "pipeline";
      try {
        List<ContextUnit> built = pipeline.apply(doc);
        stage = "store";
        deleted += persist(space, sourceId, doc.documentId(), built, guard, tally);
        docs++;
        unitCount += built.size();
      } catch (WriteGuardException e) {
        throw e; // guard 거부(점유 상실 등)는 문서 실패가 아니라 run 실패다
      } catch (RuntimeException e) {
        errors.add(
            new DeadLetter(
                doc.uri(),
                stage,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                e));
      }
    }
    // prune: 문서마다 유닛·키워드·벡터 삭제를 한 트랜잭션으로(고아 색인 행 0, 리뷰 B-I-1)
    for (String documentId : units.listDocumentIds(space, sourceId)) {
      if (liveDocumentIds.contains(documentId)) continue;
      Integer n =
          tx.execute(
              s -> {
                guard.run();
                return deleteUnits(space, sourceId, documentId, List.of());
              });
      deleted += n == null ? 0 : n;
    }
    // 커서는 수집 시작 시각 — 수집 도중 변경된 문서는 다음 sync에서 다시 잡힌다
    meta.set(space, "cursor:" + sourceId, startedAt);
    return new SyncReport(docs, unitCount, deleted, List.copyOf(errors), tally.counts());
  }

  /** 임베딩 세대(구현체·차원·입력 형식)가 다르면 공간의 전 유닛을 재임베딩한다. */
  private void backfillEmbeddings(SpaceId space, Runnable guard, EmbeddingTally tally) {
    String version = embeddingGeneration();
    if (meta.get(space, "embedding:version").filter(version::equals).isPresent()) return;
    List<ContextUnit> all = units.listAll(space);
    List<ContextUnit> embedded = attachEmbeddings(all, tally);
    tx.executeWithoutResult(
        s -> {
          guard.run();
          List<IndexedUnit> indexed = new ArrayList<>();
          for (ContextUnit u : embedded)
            indexed.add(
                new IndexedUnit(
                    u.id(),
                    u.documentId(),
                    u.partition(),
                    u.tier() == Tier.ARCHIVED,
                    u.embedding().orElseThrow()));
          if (!indexed.isEmpty()) vectors.upsert(space, indexed);
          meta.set(space, "embedding:version", version);
        });
  }

  /** 100개씩 임베딩한다 — 배치마다 캐시를 먼저 본다({@link CachedEmbeddings}). */
  private List<ContextUnit> attachEmbeddings(List<ContextUnit> list, EmbeddingTally tally) {
    List<ContextUnit> out = new ArrayList<>(list.size());
    for (int from = 0; from < list.size(); from += EMBED_BATCH) {
      List<ContextUnit> batch = list.subList(from, Math.min(list.size(), from + EMBED_BATCH));
      CachedEmbeddings.Batch vectors =
          embeddings.vectorsOf(batch.stream().map(DocumentIngestor::embeddingInputOf).toList());
      tally.fromCache += vectors.fromCache();
      tally.embedded += batch.size() - vectors.fromCache();
      for (int i = 0; i < batch.size(); i++)
        out.add(withEmbedding(batch.get(i), Optional.of(vectors.vectors().get(i))));
    }
    return out;
  }

  /** 원본 상태 갱신과 임베딩 갱신을 분리: 실제 임베딩 입력이 바뀐 유닛만 재임베딩. 저장은 임베딩 뒤 한 트랜잭션. */
  private int persist(
      SpaceId space,
      String sourceId,
      String documentId,
      List<ContextUnit> built,
      Runnable guard,
      EmbeddingTally tally) {
    List<String> ids = built.stream().map(ContextUnit::id).toList();
    Map<String, ContextUnit> previous = new HashMap<>();
    if (!ids.isEmpty()) {
      Map<String, double[]> stored = vectors.vectorsOf(space, ids);
      for (ContextUnit u : units.getMany(space, ids))
        previous.put(u.id(), withEmbedding(u, Optional.ofNullable(stored.get(u.id()))));
    }
    List<ContextUnit> toUpsert = new ArrayList<>();
    List<ContextUnit> toEmbed = new ArrayList<>();
    for (ContextUnit unit : built) {
      ContextUnit prev = previous.get(unit.id());
      ContextUnit next = preserveLifecycle(unit, prev);
      if (prev != null && sameStoredState(prev, next)) continue;
      if (prev == null || !embeddingInputOf(prev).equals(embeddingInputOf(next))) {
        toEmbed.add(withEmbedding(next, Optional.empty())); // 기존 벡터를 새 입력의 벡터로 오인하지 않는다
      } else toUpsert.add(next);
    }
    List<ContextUnit> embedded = attachEmbeddings(toEmbed, tally);
    List<ContextUnit> all = new ArrayList<>(toUpsert);
    all.addAll(embedded);
    return tx.execute(
        s -> {
          guard.run();
          if (!all.isEmpty()) {
            units.upsert(space, all);
            List<IndexedText> texts = new ArrayList<>();
            List<IndexedUnit> indexed = new ArrayList<>();
            List<String> withoutVector = new ArrayList<>();
            for (ContextUnit u : all) {
              boolean archived = u.tier() == Tier.ARCHIVED;
              texts.add(
                  new IndexedText(
                      u.id(), u.documentId(), u.title(), u.content(), u.partition(), archived));
              if (u.embedding().isPresent())
                indexed.add(
                    new IndexedUnit(
                        u.id(), u.documentId(), u.partition(), archived, u.embedding().get()));
              else withoutVector.add(u.id());
            }
            keywords.upsert(space, texts);
            if (!indexed.isEmpty()) vectors.upsert(space, indexed);
            if (!withoutVector.isEmpty())
              vectors.delete(space, withoutVector); // G7: 임베딩 없으면 벡터 행 삭제
          }
          return deleteUnits(space, sourceId, documentId, ids);
        });
  }

  private int deleteUnits(SpaceId space, String sourceId, String documentId, List<String> keepIds) {
    List<String> removed = units.deleteByDocument(space, sourceId, documentId, keepIds);
    if (!removed.isEmpty()) {
      keywords.delete(space, removed);
      vectors.delete(space, removed);
    }
    return removed.size();
  }

  /**
   * 원본 상태만 바뀐 재수집은 보관·회수·무효화 이력을 되돌리지 않는다(contentHash 같을 때
   * tier·invalidatedAt·compactedFrom·embedding 보존).
   */
  private static ContextUnit preserveLifecycle(ContextUnit unit, ContextUnit previous) {
    if (previous == null || !previous.contentHash().equals(unit.contentHash())) return unit;
    return new ContextUnit(
        unit.id(),
        unit.partition(),
        unit.title(),
        unit.content(),
        unit.contentHash(),
        unit.tokens(),
        previous.tier(),
        unit.provenance(),
        unit.importance(),
        unit.validFrom(),
        previous.invalidatedAt(),
        unit.links(),
        unit.tags(),
        unit.aliases(),
        previous.embedding(),
        previous.compactedFrom());
  }

  private static boolean sameStoredState(ContextUnit a, ContextUnit b) {
    if (!withEmbedding(a, Optional.empty()).equals(withEmbedding(b, Optional.empty())))
      return false;
    return a.embedding().isPresent() == b.embedding().isPresent()
        && (a.embedding().isEmpty() || Arrays.equals(a.embedding().get(), b.embedding().get()));
  }

  private static ContextUnit withEmbedding(ContextUnit u, Optional<double[]> embedding) {
    return new ContextUnit(
        u.id(),
        u.partition(),
        u.title(),
        u.content(),
        u.contentHash(),
        u.tokens(),
        u.tier(),
        u.provenance(),
        u.importance(),
        u.validFrom(),
        u.invalidatedAt(),
        u.links(),
        u.tags(),
        u.aliases(),
        embedding,
        u.compactedFrom());
  }

  static Set<String> ids(List<ContextUnit> list) {
    return new HashSet<>(list.stream().map(ContextUnit::id).toList());
  }
}
