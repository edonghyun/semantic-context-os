package io.scos.store.memory;

import io.scos.spi.store.UnitStore;
import io.scos.spi.store.UnitStoreContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("유닛 저장소 계약 — 메모리 포트(CONTRACTS A1·D·E)")
class MemoryUnitStoreTest extends UnitStoreContract {
  @Override
  protected UnitStore newStore() {
    return new MemoryUnitStore();
  }
}
