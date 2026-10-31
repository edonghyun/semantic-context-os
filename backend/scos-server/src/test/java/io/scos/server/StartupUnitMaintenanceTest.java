package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitMaintenanceContract;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("기동 조회 기록 포트는 공통 유지보수 계약을 지킨다")
class StartupUnitMaintenanceTest extends UnitMaintenanceContract {
  private MemoryUnitStore units;
  private MemoryVectorIndex vectors;
  private StartupUnitMaintenance startup;

  @Override
  protected UnitMaintenance newMaintenance() {
    units = new MemoryUnitStore();
    vectors = new MemoryVectorIndex();
    startup = new StartupUnitMaintenance(new MemoryUnitMaintenance(units, vectors));
    return startup;
  }

  @Override
  protected UnitStore unitStore() {
    return units;
  }

  @Override
  protected VectorIndex vectorIndex() {
    return vectors;
  }

  @Test
  @DisplayName("압축 개수를 조회하면 호출 수를 기록한다")
  void recordsCompactedRead() {
    startup.countCompacted();

    // then
    assertThat(startup.compactedReads()).isOne();
  }
}
