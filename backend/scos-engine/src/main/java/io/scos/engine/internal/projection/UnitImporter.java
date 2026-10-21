package io.scos.engine.internal.projection;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 유닛 적재 유스케이스(TS SqliteStore.upsert 경로의 Java 판) — 유닛 저장·키워드 색인·벡터 색인을 **한 트랜잭션**에 쓴다. 원자성은 세 어댑터가
 * 같은 DataSource/TransactionManager를 공유하는 조립의 성질이다(ADR-0006·0007, DESIGN-PATTERNS §10); 어댑터가 외부 저장소로
 * 갈라지면 Outbox 경로가 흡수한다. 임베딩이 없는 유닛은 벡터 색인에 넣지 않는다.
 */
public final class UnitImporter {
  public record Imported(int units, int vectors) {}

  private final TransactionTemplate tx;
  private final UnitStore units;
  private final KeywordIndex keywords;
  private final VectorIndex vectors;

  public UnitImporter(
      TransactionTemplate tx, UnitStore units, KeywordIndex keywords, VectorIndex vectors) {
    this.tx = tx;
    this.units = units;
    this.keywords = keywords;
    this.vectors = vectors;
  }

  public Imported importUnits(SpaceId space, List<ContextUnit> batch) {
    return tx.execute(
        status -> {
          units.upsert(space, batch);
          List<IndexedText> texts = new ArrayList<>(batch.size());
          List<IndexedUnit> embedded = new ArrayList<>();
          for (ContextUnit u : batch) {
            boolean archived = u.tier() == Tier.ARCHIVED;
            texts.add(
                new IndexedText(
                    u.id(), u.documentId(), u.title(), u.content(), u.partition(), archived));
            u.embedding()
                .ifPresent(
                    v ->
                        embedded.add(
                            new IndexedUnit(u.id(), u.documentId(), u.partition(), archived, v)));
          }
          keywords.upsert(space, texts);
          if (!embedded.isEmpty()) vectors.upsert(space, embedded);
          return new Imported(batch.size(), embedded.size());
        });
  }
}
