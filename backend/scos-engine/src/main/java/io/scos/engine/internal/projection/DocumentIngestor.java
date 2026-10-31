package io.scos.engine.internal.projection;

import static io.scos.engine.internal.projection.EmbedderUnitEmbedding.inputOf;
import static io.scos.engine.internal.projection.UnitState.withEmbedding;
import static io.scos.engine.internal.projection.UnitState.withLifecycle;

import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.engine.EmbeddingGenerationMismatchException;
import io.scos.engine.internal.projection.UnitEmbedding.Tally;
import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.engine.support.projection.DeadLetter;
import io.scos.engine.support.projection.SourceSync;
import io.scos.engine.support.projection.SyncReport;
import io.scos.engine.support.projection.WriteGuardException;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitMaintenance;
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
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 문서 인제스트(G5·G6·G9): 문서마다 파이프라인 → 기존 유닛과 대조해 바뀐 것만 저장·재임베딩 → 문서의 나머지 유닛 삭제(keepIds), 끝에 prune(현존 목록
 * 밖 문서 삭제). 유닛 id가 결정론이라 재시도·재투영에 중복 유닛이 생기지 않는다(불변식 4). 문서 단위 실패는 DeadLetter로 격리하고 다른 문서는 계속한다. 한
 * 문서의 저장(유닛·키워드·벡터·삭제)은 한 트랜잭션이다(어댑터 조합의 성질, DESIGN-PATTERNS §10). 공간의 임베딩 세대가 이 임베더와 다르거나 재임베딩이
 * 끝나지 않았으면 쓰지 않고 거부한다 — 시작 때 한 번, 쓰기 트랜잭션마다 잠가 읽어 한 번(stage2 §3.3). 다시 임베딩은 {@code scos reembed}로만
 * 한다.
 */
public final class DocumentIngestor implements SourceSync {
  private final TransactionOperations tx;
  private final UnitMaintenance units;
  private final KeywordIndex keywords;
  private final VectorIndex vectors;
  private final SpaceMeta meta;
  private final UnitEmbedding embedding;
  private final EmbeddingGeneration generation;
  private final IngestPipeline pipeline;
  private final Clock clock;

  /** 임베딩 캐시 없이(모든 입력을 임베더로). */
  public DocumentIngestor(
      TransactionOperations tx,
      UnitMaintenance units,
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
      UnitMaintenance units,
      KeywordIndex keywords,
      VectorIndex vectors,
      SpaceMeta meta,
      Embedder embedder,
      EmbeddingCache cache,
      IngestPipeline pipeline,
      Clock clock) {
    this(
        tx,
        units,
        keywords,
        vectors,
        meta,
        new EmbedderUnitEmbedding(embedder, cache),
        pipeline,
        clock);
  }

  /** 벡터를 붙이는 방식을 직접 고른다 — 키워드만 쓰는 조립은 {@link KeywordOnlyUnitEmbedding}. */
  public DocumentIngestor(
      TransactionOperations tx,
      UnitMaintenance units,
      KeywordIndex keywords,
      VectorIndex vectors,
      SpaceMeta meta,
      UnitEmbedding embedding,
      IngestPipeline pipeline,
      Clock clock) {
    this.tx = tx;
    this.units = units;
    this.keywords = keywords;
    this.vectors = vectors;
    this.meta = meta;
    this.embedding = embedding;
    this.generation = embedding.generation();
    this.pipeline = pipeline;
    this.clock = clock;
  }

  /** 이 임베더의 임베딩 세대(stage2 §3.1). 임베딩 캐시의 키 공간이기도 하다(ADR-0026). */
  public String embeddingGeneration() {
    return generation.value();
  }

  public SyncReport sync(
      SpaceId space, String sourceId, List<SourceDocument> documents, Set<String> liveDocumentIds) {
    return sync(space, sourceId, documents, liveDocumentIds, () -> {});
  }

  /**
   * guard는 쓰기 트랜잭션마다 그 안에서 먼저 실행된다 — 투영 점유 검사(저장 경계 fencing). 던지면 그 트랜잭션은 롤백된다. {@link
   * WriteGuardException}과 {@link EmbeddingGenerationMismatchException}은 문서 격리 없이 전파되고, 다른 예외는 그 문서만
   * 격리한다.
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
    Tally tally = new Tally();
    EmbeddingGenerationCheck.verify(meta, List.of(space), generation);
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
      } catch (WriteGuardException | EmbeddingGenerationMismatchException e) {
        throw e; // guard 거부(점유 상실 등)·세대 불일치는 문서 실패가 아니라 run 실패다
      } catch (RuntimeException e) {
        errors.add(deadLetter(doc, stage, e));
      }
    }
    deleted += prune(space, sourceId, liveDocumentIds, guard);
    // 커서는 수집 시작 시각 — 수집 도중 변경된 문서는 다음 sync에서 다시 잡힌다
    meta.set(space, "cursor:" + sourceId, startedAt);
    return new SyncReport(docs, unitCount, deleted, List.copyOf(errors), tally.counts());
  }

  private static DeadLetter deadLetter(SourceDocument doc, String stage, RuntimeException e) {
    return new DeadLetter(
        doc.uri(),
        stage,
        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
        e);
  }

  /** 현존 목록 밖 문서를 지운다: 문서마다 유닛·키워드·벡터 삭제를 한 트랜잭션으로(고아 색인 행 0). */
  private int prune(SpaceId space, String sourceId, Set<String> live, Runnable guard) {
    int deleted = 0;
    for (String documentId : units.listDocumentIds(space, sourceId)) {
      if (live.contains(documentId)) continue;
      Integer n =
          tx.execute(
              s -> {
                guard.run();
                requireGeneration(space);
                return deleteUnits(space, sourceId, documentId, List.of());
              });
      deleted += n == null ? 0 : n;
    }
    return deleted;
  }

  /**
   * 쓰기 트랜잭션 안에서 공간의 세대를 잠가 읽는다 — 커밋까지 재임베딩이 바꾸지 못한다. 비어 있으면 이 세대로 기록하고, 동시에 다른 세대가 먼저 기록했으면 그 값으로
   * 거부한다.
   */
  private void requireGeneration(SpaceId space) {
    String key = EmbeddingGenerationCheck.META_KEY;
    String stored =
        meta.getLocked(space, key)
            .orElseGet(() -> meta.putIfAbsent(space, key, generation.value()));
    EmbeddingGenerationCheck.requireMatches(space, stored, generation);
  }

  /** 원본 상태 갱신과 임베딩 갱신을 분리: 실제 임베딩 입력이 바뀐 유닛만 재임베딩. 저장은 임베딩 뒤 한 트랜잭션. */
  private int persist(
      SpaceId space,
      String sourceId,
      String documentId,
      List<ContextUnit> built,
      Runnable guard,
      Tally tally) {
    List<String> ids = built.stream().map(ContextUnit::id).toList();
    UnitDiff diff = UnitDiff.of(previousUnits(space, ids), built);
    List<ContextUnit> all = new ArrayList<>(diff.toUpsert());
    all.addAll(embedding.attach(diff.toEmbed(), tally));
    return tx.execute(
        s -> {
          guard.run();
          requireGeneration(space);
          write(space, all);
          return deleteUnits(space, sourceId, documentId, ids);
        });
  }

  /** 이 id들의 저장된 유닛(저장된 벡터를 실은 채). */
  private Map<String, ContextUnit> previousUnits(SpaceId space, List<String> ids) {
    Map<String, ContextUnit> previous = new HashMap<>();
    if (ids.isEmpty()) return previous;
    Map<String, double[]> stored = units.storedVectors(space, ids);
    for (ContextUnit u : units.previousUnits(space, ids))
      previous.put(u.id(), withEmbedding(u, Optional.ofNullable(stored.get(u.id()))));
    return previous;
  }

  /**
   * 새로 만든 유닛과 저장된 유닛의 차이: 저장 상태가 같으면 건너뛰고, 임베딩 입력이 같으면 그대로 다시 쓰고(toUpsert), 새 유닛이거나 입력이 바뀌었으면 벡터 없이
   * 재임베딩한다(toEmbed — 기존 벡터를 새 입력의 벡터로 오인하지 않는다).
   */
  private record UnitDiff(List<ContextUnit> toUpsert, List<ContextUnit> toEmbed) {
    static UnitDiff of(Map<String, ContextUnit> previous, List<ContextUnit> built) {
      List<ContextUnit> toUpsert = new ArrayList<>();
      List<ContextUnit> toEmbed = new ArrayList<>();
      for (ContextUnit unit : built) {
        ContextUnit prev = previous.get(unit.id());
        ContextUnit next = preserveLifecycle(unit, prev);
        if (prev != null && sameStoredState(prev, next)) continue;
        if (prev == null || !inputOf(prev).equals(inputOf(next)))
          toEmbed.add(withEmbedding(next, Optional.empty()));
        else toUpsert.add(next);
      }
      return new UnitDiff(toUpsert, toEmbed);
    }
  }

  /** 유닛·키워드 행·벡터 행을 쓴다(호출자 트랜잭션). 임베딩이 없는 유닛은 벡터 행을 지운다(G7). */
  private void write(SpaceId space, List<ContextUnit> all) {
    if (all.isEmpty()) return;
    units.upsert(space, all);
    UnitIndexRows rows = UnitIndexRows.of(all);
    keywords.upsert(space, rows.texts());
    if (!rows.vectors().isEmpty()) vectors.upsert(space, rows.vectors());
    if (!rows.withoutVector().isEmpty()) vectors.delete(space, rows.withoutVector());
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
  private static ContextUnit preserveLifecycle(ContextUnit unit, @Nullable ContextUnit previous) {
    if (previous == null || !previous.contentHash().equals(unit.contentHash())) return unit;
    return withLifecycle(
        unit,
        previous.tier(),
        previous.invalidatedAt(),
        previous.embedding(),
        previous.compactedFrom());
  }

  private static boolean sameStoredState(ContextUnit a, ContextUnit b) {
    if (!withEmbedding(a, Optional.empty()).equals(withEmbedding(b, Optional.empty())))
      return false;
    return a.embedding().isPresent() == b.embedding().isPresent()
        && (a.embedding().isEmpty() || Arrays.equals(a.embedding().get(), b.embedding().get()));
  }

  static Set<String> ids(List<ContextUnit> list) {
    return new HashSet<>(list.stream().map(ContextUnit::id).toList());
  }
}
