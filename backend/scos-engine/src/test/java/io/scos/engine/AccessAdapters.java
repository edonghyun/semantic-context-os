package io.scos.engine;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryIdentitySeed;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.util.List;
import java.util.function.Consumer;

/** 한 코퍼스의 읽기·적재 포트와 같은 접근 디렉터리를 심는 어댑터 묶음. */
record AccessAdapters(
    AclStore acls,
    UnitStore units,
    KeywordIndex keywords,
    VectorIndex vectors,
    UnitMaintenance maintenance,
    IdentityStore identities,
    Consumer<AccessDirectory> seed) {
  void load(SpaceId space, List<ContextUnit> corpus) {
    maintenance.upsert(space, corpus);
    keywords.upsert(
        space,
        corpus.stream()
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
        corpus.stream()
            .filter(u -> u.embedding().isPresent())
            .map(
                u ->
                    new IndexedUnit(
                        u.id(),
                        u.documentId(),
                        u.partition(),
                        u.tier() == Tier.ARCHIVED,
                        u.embedding().orElseThrow()))
            .toList());
  }

  static AccessAdapters memory() {
    var acls = new MemoryAclStore();
    var units = new MemoryUnitStore(acls);
    var vectors = new MemoryVectorIndex(acls);
    var identities = new MemoryIdentityStore();
    return new AccessAdapters(
        acls,
        units,
        new MemoryKeywordIndex(acls),
        vectors,
        new MemoryUnitMaintenance(units, vectors),
        identities,
        new MemoryIdentitySeed(identities)::seed);
  }
}
