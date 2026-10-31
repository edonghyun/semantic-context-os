package io.scos.engine.internal.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitMaintenanceContract;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("기록용 유지보수 포트도 공통 유지보수 계약을 지킨다")
class RecordingUnitMaintenanceTest extends UnitMaintenanceContract {
  private MemoryUnitStore units;
  private MemoryVectorIndex vectors;
  private RecordingUnitMaintenance recording;

  @Override
  protected UnitMaintenance newMaintenance() {
    units = new MemoryUnitStore();
    vectors = new MemoryVectorIndex();
    recording = new RecordingUnitMaintenance(units, vectors);
    return recording;
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
  @DisplayName("최신성 조회를 호출하면 호출 수를 기록한다")
  void recordsLatestRead() {
    // when
    recording.latestCapturedAt(new SpaceId("empty"));

    // then
    assertThat(recording.latestReads()).isOne();
  }
}
