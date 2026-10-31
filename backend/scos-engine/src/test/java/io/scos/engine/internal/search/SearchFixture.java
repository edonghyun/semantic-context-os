package io.scos.engine.internal.search;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.util.List;

/**
 * 검색 서비스 단위 테스트용 메모리 조립(상태 준비, TESTING §3.1). 유닛을 저장·키워드·벡터에 함께 넣는다(UnitImporter와 같은 규칙). 데이터는
 * {@link io.scos.spi.store.ContextUnits}로 만든다.
 */
public final class SearchFixture {
  public static final SpaceId S = new SpaceId("legacy");
  public final MemoryUnitStore units;
  public final MemoryKeywordIndex keywords;
  public final MemoryVectorIndex vectors;
  public final MemoryUnitMaintenance maintenance;

  public SearchFixture() {
    this(new MemoryAclStore());
  }

  SearchFixture(MemoryAclStore acls) {
    units = new MemoryUnitStore(acls);
    keywords = new MemoryKeywordIndex(acls);
    vectors = new MemoryVectorIndex(acls);
    maintenance = new MemoryUnitMaintenance(units, vectors);
  }

  public void load(SpaceId space, ContextUnit... list) {
    List<ContextUnit> batch = List.of(list);
    maintenance.upsert(space, batch);
    keywords.upsert(
        space,
        batch.stream()
            .map(
                u ->
                    new IndexedText(
                        u.id(),
                        u.documentId(),
                        u.title(),
                        u.content(),
                        u.partition(),
                        u.tier() == Tier.ARCHIVED))
            .toList());
    vectors.upsert(
        space,
        batch.stream()
            .filter(u -> u.embedding().isPresent())
            .map(
                u ->
                    new IndexedUnit(
                        u.id(),
                        u.documentId(),
                        u.partition(),
                        u.tier() == Tier.ARCHIVED,
                        u.embedding().get()))
            .toList());
  }
}
