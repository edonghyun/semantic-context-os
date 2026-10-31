package io.scos.engine.internal.projection;

import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.VectorIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 공간 재임베딩(stage2 §3.3, `scos reembed`): 공간의 모든 유닛을 설정 임베더로 다시 임베딩하고 공간 세대를 그 임베더의 세대로 바꾼다. 절차는
 * {@code pending:<목표>} 커밋 → 100개씩 캐시 우선 임베딩·배치마다 벡터 쓰기 → 목표 세대 기록이다. pending인 동안 투영·기동은 거부되므로(세대
 * 검사) 섞인 벡터로 검색하지 않는다. 중간에 죽으면 pending이 남고, 다시 실행하면 처음부터 끝까지 간다.
 */
public final class SpaceReembedding {
  /**
   * 실행 전에 보이는 재임베딩 계획.
   *
   * @param units 공간의 유닛 수
   * @param stored 공간에 저장된 세대({@code pending:} 표시 포함), 없으면 empty
   * @param target 설정 임베더의 세대
   */
  public record Plan(
      SpaceId space, int units, Optional<String> stored, EmbeddingGeneration target) {}

  /** 실행이 어떻게 끝났는가. {@link Outcome#REEMBEDDED}만 이 실행이 목표 세대를 기록했다. */
  public enum Outcome {
    /** 모든 유닛을 다시 임베딩하고 목표 세대를 기록했다. */
    REEMBEDDED,
    /** 이미 목표 세대였다 — 아무것도 쓰지 않았다. */
    ALREADY_CURRENT,
    /** 유닛도 저장 세대도 없는 공간(새 공간이거나 잘못 적은 공간 id) — 아무것도 쓰지 않았다. */
    EMPTY_SPACE,
    /** 같은 목표의 다른 실행이 먼저 끝내 목표 세대를 기록했다 — 남은 벡터를 쓰지 않고 멈췄다. */
    COMPLETED_BY_OTHER_RUN
  }

  /**
   * 실행 결과.
   *
   * @param units 이 실행이 벡터를 쓴 유닛 수
   * @param fromCache 그중 캐시에서 얻은 수
   * @param embedded 그중 임베더로 얻은 수
   */
  public record Report(
      SpaceId space,
      int units,
      int fromCache,
      int embedded,
      EmbeddingGeneration generation,
      Outcome outcome) {}

  private final TransactionOperations tx;
  private final UnitMaintenance units;
  private final VectorIndex vectors;
  private final SpaceMeta meta;
  private final EmbeddingGeneration target;
  private final CachedEmbeddings embeddings;

  public SpaceReembedding(
      TransactionOperations tx,
      UnitMaintenance units,
      VectorIndex vectors,
      SpaceMeta meta,
      Embedder embedder,
      EmbeddingCache cache) {
    this.tx = tx;
    this.units = units;
    this.vectors = vectors;
    this.meta = meta;
    this.target = EmbeddingGenerationCheck.generationOf(embedder);
    this.embeddings = new CachedEmbeddings(embedder, cache, target.value());
  }

  /** 쓰지 않고 무엇을 할지만 낸다(`reembed --dry-run`). */
  public Plan plan(SpaceId space) {
    return new Plan(space, units.listAll(space).size(), stored(space), target);
  }

  public Report run(SpaceId space) {
    Optional<String> stored = stored(space);
    if (stored.filter(target.value()::equals).isPresent())
      return new Report(space, 0, 0, 0, target, Outcome.ALREADY_CURRENT);
    // 쓸 유닛도 지킬 세대도 없다 — 잘못 적은 공간 id에 pending·세대 행을 남기지 않는다
    if (stored.isEmpty() && units.listAll(space).isEmpty())
      return new Report(space, 0, 0, 0, target, Outcome.EMPTY_SPACE);
    String pending = EmbeddingGenerationCheck.PENDING_PREFIX + target.value();
    // 짧은 트랜잭션 하나로 먼저 커밋한다 — 쓰기 중인 투영(세대를 FOR SHARE로 잠근)이 끝나기를 기다리고, 그 뒤 투영은 pending을 보고 거부된다
    tx.executeWithoutResult(s -> meta.set(space, EmbeddingGenerationCheck.META_KEY, pending));
    List<ContextUnit> all = units.listAll(space);
    int written = 0;
    int fromCache = 0;
    for (int from = 0; from < all.size(); from += EmbedderUnitEmbedding.EMBED_BATCH) {
      List<ContextUnit> batch =
          all.subList(from, Math.min(all.size(), from + EmbedderUnitEmbedding.EMBED_BATCH));
      CachedEmbeddings.Batch embedded =
          embeddings.vectorsOf(batch.stream().map(EmbedderUnitEmbedding::inputOf).toList());
      List<ContextUnit> reembedded = new ArrayList<>(batch.size());
      for (int i = 0; i < batch.size(); i++)
        reembedded.add(
            UnitState.withEmbedding(batch.get(i), Optional.of(embedded.vectors().get(i))));
      List<IndexedUnit> indexed = UnitIndexRows.of(reembedded).vectors();
      boolean ours =
          Boolean.TRUE.equals(
              tx.execute(
                  s -> {
                    if (!stillPending(space, pending)) return false;
                    vectors.upsert(space, indexed);
                    return true;
                  }));
      if (!ours) return completedByOtherRun(space, written, fromCache);
      written += batch.size();
      fromCache += embedded.fromCache();
    }
    boolean recorded =
        Boolean.TRUE.equals(
            tx.execute(
                s -> {
                  if (!stillPending(space, pending)) return false;
                  meta.set(space, EmbeddingGenerationCheck.META_KEY, target.value());
                  return true;
                }));
    if (!recorded) return completedByOtherRun(space, written, fromCache);
    return new Report(space, written, fromCache, written - fromCache, target, Outcome.REEMBEDDED);
  }

  private Report completedByOtherRun(SpaceId space, int written, int fromCache) {
    return new Report(
        space, written, fromCache, written - fromCache, target, Outcome.COMPLETED_BY_OTHER_RUN);
  }

  /**
   * 쓰기 트랜잭션 안에서 공간 세대를 잠가 읽어, 아직 이 재임베딩의 표시({@code pending:<목표>})인지 본다. 잠금은 커밋까지 유지되어 덮어쓰기는 이 쓰기가
   * 끝난 뒤에야 일어난다.
   *
   * <ul>
   *   <li>표시 그대로면 true — 써도 된다.
   *   <li>목표 세대면 false — 같은 목표의 다른 실행이 모든 유닛을 끝냈다. 그 뒤 투영이 바꾸거나 지운 유닛을 옛 목록으로 덮지 않도록 더 쓰지 않는다.
   *   <li>그 밖(다른 목표의 pending·다른 세대·없음)이면 거부한다 — 다른 재임베딩이 가져갔다.
   * </ul>
   */
  private boolean stillPending(SpaceId space, String pending) {
    String stored = meta.getLocked(space, EmbeddingGenerationCheck.META_KEY).orElse("");
    if (stored.equals(pending)) return true;
    if (stored.equals(target.value())) return false;
    throw new IllegalStateException(
        "space "
            + space.value()
            + ": re-embedding to "
            + target.value()
            + " was superseded (stored embedding generation "
            + (stored.isEmpty() ? "none" : stored)
            + "). Stopped without writing further vectors.");
  }

  private Optional<String> stored(SpaceId space) {
    return meta.get(space, EmbeddingGenerationCheck.META_KEY);
  }
}
