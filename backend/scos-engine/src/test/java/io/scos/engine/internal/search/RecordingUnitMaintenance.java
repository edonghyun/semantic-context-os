package io.scos.engine.internal.search;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 저장 의미는 공통 계약을 통과한 메모리 어댑터에 맡기고 최신성 조회만 기록한다. */
final class RecordingUnitMaintenance implements UnitMaintenance {
  private final UnitMaintenance delegate;
  private int latestReads;

  RecordingUnitMaintenance(MemoryUnitStore units, MemoryVectorIndex vectors) {
    delegate = new MemoryUnitMaintenance(units, vectors);
  }

  int latestReads() {
    return latestReads;
  }

  @Override
  public long countCompacted() {
    return delegate.countCompacted();
  }

  @Override
  public void upsert(SpaceId space, List<ContextUnit> units) {
    delegate.upsert(space, units);
  }

  @Override
  public List<ContextUnit> previousUnits(SpaceId space, List<String> ids) {
    return delegate.previousUnits(space, ids);
  }

  @Override
  public Map<String, double[]> storedVectors(SpaceId space, List<String> ids) {
    return delegate.storedVectors(space, ids);
  }

  @Override
  public List<String> deleteByDocument(
      SpaceId space, String source, String document, List<String> keep) {
    return delegate.deleteByDocument(space, source, document, keep);
  }

  @Override
  public List<String> listDocumentIds(SpaceId space, String source) {
    return delegate.listDocumentIds(space, source);
  }

  @Override
  public List<ContextUnit> listAll(SpaceId space) {
    return delegate.listAll(space);
  }

  @Override
  public Optional<String> latestCapturedAt(SpaceId space) {
    latestReads++;
    return delegate.latestCapturedAt(space);
  }
}
