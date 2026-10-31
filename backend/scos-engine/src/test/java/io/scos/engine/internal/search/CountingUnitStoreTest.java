package io.scos.engine.internal.search;

import io.scos.spi.access.AclStore;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.UnitStoreContract;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import org.junit.jupiter.api.DisplayName;

@DisplayName("조회 횟수 대역은 실제 어댑터와 같은 유닛 저장소 계약을 지킨다")
class CountingUnitStoreTest extends UnitStoreContract {
  private MemoryUnitStore units;
  private MemoryAclStore acl;

  @Override
  protected UnitStore newStore() {
    acl = new MemoryAclStore();
    units = new MemoryUnitStore(acl);
    return new CountingUnitStore(units);
  }

  @Override
  protected AclStore aclStore() {
    return acl;
  }

  @Override
  protected UnitMaintenance maintenance() {
    return new MemoryUnitMaintenance(units, new MemoryVectorIndex());
  }
}
