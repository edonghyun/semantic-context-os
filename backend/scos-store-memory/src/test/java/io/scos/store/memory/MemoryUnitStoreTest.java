package io.scos.store.memory;

import io.scos.spi.access.AclStore;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.UnitStoreContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("유닛 저장소 계약 — 메모리 포트(CONTRACTS A1·D·E)")
class MemoryUnitStoreTest extends UnitStoreContract {
  private MemoryUnitStore units;
  private final MemoryAclStore acl = new MemoryAclStore();

  @Override
  protected AclStore aclStore() {
    return acl;
  }

  @Override
  protected UnitMaintenance maintenance() {
    return new MemoryUnitMaintenance(units, new MemoryVectorIndex());
  }

  @Override
  protected UnitStore newStore() {
    units = new MemoryUnitStore(acl);
    return units;
  }
}
