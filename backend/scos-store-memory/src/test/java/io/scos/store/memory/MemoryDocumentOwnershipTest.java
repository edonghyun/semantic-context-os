package io.scos.store.memory;

import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.DocumentOwnershipContract;
import io.scos.spi.store.UnitMaintenance;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 문서 소유는 공통 계약을 지킨다")
class MemoryDocumentOwnershipTest extends DocumentOwnershipContract {
  private UnitMaintenance units;

  @Override
  protected DocumentOwnership newOwnership() {
    var ownership = new MemoryDocumentOwnership();
    units =
        new MemoryUnitMaintenance(
            new MemoryUnitStore(new MemoryAclStore(), ownership), new MemoryVectorIndex());
    return ownership;
  }

  @Override
  protected UnitMaintenance maintenance() {
    return units;
  }
}
