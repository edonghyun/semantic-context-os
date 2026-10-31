package io.scos.store.memory;

import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitMaintenanceContract;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import org.junit.jupiter.api.DisplayName;

@DisplayName("유지보수 포트 계약 — 메모리 포트(CONTRACTS P)")
class MemoryUnitMaintenanceTest extends UnitMaintenanceContract {
  private MemoryUnitStore units;
  private MemoryVectorIndex vectors;

  @Override
  protected UnitMaintenance newMaintenance() {
    units = new MemoryUnitStore();
    vectors = new MemoryVectorIndex();
    return new MemoryUnitMaintenance(units, vectors);
  }

  @Override
  protected UnitStore unitStore() {
    return units;
  }

  @Override
  protected VectorIndex vectorIndex() {
    return vectors;
  }
}
