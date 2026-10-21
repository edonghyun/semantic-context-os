package io.scos.store.memory;

import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.SpaceMetaContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("공간 메타 계약 — 메모리 포트(ADR-0018 §6)")
class MemorySpaceMetaTest extends SpaceMetaContract {
  @Override
  protected SpaceMeta newMeta() {
    return new MemorySpaceMeta();
  }
}
