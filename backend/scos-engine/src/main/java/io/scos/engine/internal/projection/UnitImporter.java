package io.scos.engine.internal.projection;

import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.VectorIndex;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 유닛 적재 유스케이스 — 유닛 저장·키워드 색인·벡터 색인을 **한 트랜잭션**에 쓴다. 원자성은 세 어댑터가 같은 DataSource/TransactionManager를
 * 공유하는 조립의 성질이다(ADR-0006·0007, DESIGN-PATTERNS §10); 어댑터가 외부 저장소로 갈라지면 Outbox 경로가 흡수한다. 임베딩이 없는 유닛은
 * 벡터 색인에 넣지 않는다.
 *
 * <p>벡터를 동봉한 적재는 그 벡터의 세대를 밝혀야 하고, 그 세대·차원이 설정 임베더와 같아야 한다(stage2 §3.3). 쓰기 트랜잭션 안에서 공간 세대를 잠가 대조하고
 * 비어 있으면 기록한다 — {@link DocumentIngestor}와 같은 규칙이라, 적재 도중 재임베딩이 pending을 기록해도 섞인 벡터가 커밋되지 않는다. 벡터가
 * 없는 적재는 세대를 보지 않는다(벡터 채널에 들어가지 않는다).
 */
public final class UnitImporter {
  public record Imported(int units, int vectors) {}

  /** 벡터를 동봉했는데 그 벡터의 세대를 밝히지 않았다 — 명령은 사용법 오류로 본다. */
  public static final class UndeclaredGenerationException extends IllegalArgumentException {
    UndeclaredGenerationException() {
      super("units carry embeddings but no embedding generation was declared");
    }
  }

  private final TransactionTemplate tx;
  private final UnitMaintenance units;
  private final KeywordIndex keywords;
  private final VectorIndex vectors;
  private final SpaceMeta meta;
  private final EmbeddingGeneration configured;

  public UnitImporter(
      TransactionTemplate tx,
      UnitMaintenance units,
      KeywordIndex keywords,
      VectorIndex vectors,
      SpaceMeta meta,
      EmbeddingGeneration configured) {
    this.tx = tx;
    this.units = units;
    this.keywords = keywords;
    this.vectors = vectors;
    this.meta = meta;
    this.configured = configured;
  }

  public Imported importUnits(
      SpaceId space, List<ContextUnit> batch, Optional<EmbeddingGeneration> declared) {
    boolean carriesEmbeddings = batch.stream().anyMatch(u -> u.embedding().isPresent());
    if (carriesEmbeddings) requireConfigured(batch, declared);
    return tx.execute(
        status -> {
          if (carriesEmbeddings) requireSpaceGeneration(space);
          units.upsert(space, batch);
          UnitIndexRows rows = UnitIndexRows.of(batch);
          keywords.upsert(space, rows.texts());
          if (!rows.vectors().isEmpty()) vectors.upsert(space, rows.vectors());
          return new Imported(batch.size(), rows.vectors().size());
        });
  }

  /** 밝힌 세대가 설정 세대이고 모든 벡터가 설정 차원인가. */
  private void requireConfigured(List<ContextUnit> batch, Optional<EmbeddingGeneration> declared) {
    if (declared.isEmpty()) throw new UndeclaredGenerationException();
    if (!declared.get().equals(configured))
      throw new IllegalArgumentException(
          "declared embedding generation "
              + declared.get().value()
              + " differs from configured "
              + configured.value());
    for (ContextUnit u : batch) {
      int dim = u.embedding().map(v -> v.length).orElse(configured.dim());
      if (dim != configured.dim())
        throw new IllegalArgumentException(
            "unit "
                + u.id()
                + ": embedding has "
                + dim
                + " dimensions, configured "
                + configured.dim());
    }
  }

  /**
   * 쓰기 트랜잭션 안에서 공간 세대를 잠가 읽는다 — 커밋까지 재임베딩이 바꾸지 못한다. 비어 있으면 설정 세대로 기록하고(유닛만 있고 세대가 없는 공간도 같다), 동시에
   * 다른 세대가 먼저 기록했으면 그 값으로 거부한다.
   */
  private void requireSpaceGeneration(SpaceId space) {
    String key = EmbeddingGenerationCheck.META_KEY;
    String stored =
        meta.getLocked(space, key)
            .orElseGet(() -> meta.putIfAbsent(space, key, configured.value()));
    EmbeddingGenerationCheck.requireMatches(space, stored, configured);
  }
}
