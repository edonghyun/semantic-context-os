package io.scos.server;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 저장 의미는 위임하고, 기동 검사의 전체 공간 압축 조회만 관측한다. */
final class StartupUnitMaintenance implements UnitMaintenance {
  private final UnitMaintenance delegate;
  private int compactedReads;

  StartupUnitMaintenance(UnitMaintenance delegate) {
    this.delegate = delegate;
  }

  int compactedReads() {
    return compactedReads;
  }

  @Override
  public long countCompacted() {
    compactedReads++;
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
  public Map<String, double[]> storedVectors(SpaceId space, List<String> unitIds) {
    return delegate.storedVectors(space, unitIds);
  }

  @Override
  public List<String> deleteByDocument(
      SpaceId space, String sourceId, String documentId, List<String> keepIds) {
    return delegate.deleteByDocument(space, sourceId, documentId, keepIds);
  }

  @Override
  public List<String> listDocumentIds(SpaceId space, String sourceId) {
    return delegate.listDocumentIds(space, sourceId);
  }

  @Override
  public List<ContextUnit> listAll(SpaceId space) {
    return delegate.listAll(space);
  }

  @Override
  public Optional<String> latestCapturedAt(SpaceId space) {
    return delegate.latestCapturedAt(space);
  }
}
