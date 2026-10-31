package io.scos.store.sqlite;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 같은 저장 상태의 유닛 저장소와 벡터 테이블(V1 {@code vectors})을 적재·유지보수 포트로 묶는다. */
public final class SqliteUnitMaintenance implements UnitMaintenance {
  private final SqliteUnitStore units;
  private final SqliteVectorIndex vectors;

  public SqliteUnitMaintenance(SqliteUnitStore units, SqliteVectorIndex vectors) {
    this.units = units;
    this.vectors = vectors;
  }

  @Override
  public long countCompacted() {
    return units.countCompacted();
  }

  @Override
  public void upsert(SpaceId space, List<ContextUnit> batch) {
    units.upsert(space, batch);
  }

  @Override
  public List<ContextUnit> previousUnits(SpaceId space, List<String> ids) {
    return units.previousUnits(space, ids);
  }

  @Override
  public Map<String, double[]> storedVectors(SpaceId space, List<String> unitIds) {
    return vectors.storedVectors(space, unitIds);
  }

  @Override
  public List<String> deleteByDocument(
      SpaceId space, String sourceId, String documentId, List<String> keepIds) {
    return units.deleteByDocument(space, sourceId, documentId, keepIds);
  }

  @Override
  public List<String> listDocumentIds(SpaceId space, String sourceId) {
    return units.listDocumentIds(space, sourceId);
  }

  @Override
  public List<ContextUnit> listAll(SpaceId space) {
    return units.listAll(space);
  }

  @Override
  public Optional<String> latestCapturedAt(SpaceId space) {
    return units.latestCapturedAt(space);
  }
}
