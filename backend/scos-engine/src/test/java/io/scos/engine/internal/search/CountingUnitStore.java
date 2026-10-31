package io.scos.engine.internal.search;

import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import java.util.List;

/** 실제 저장소에 위임하면서 문서 페이지 조회 비용만 관찰한다. */
final class CountingUnitStore implements UnitStore {
  private final UnitStore delegate;
  private int documentReads;

  CountingUnitStore(UnitStore delegate) {
    this.delegate = delegate;
  }

  int documentReads() {
    return documentReads;
  }

  @Override
  public List<ContextUnit> getMany(SpaceId space, AccessScope access, List<String> ids) {
    return delegate.getMany(space, access, ids);
  }

  @Override
  public List<ContextUnit> listByDocument(
      SpaceId space, AccessScope access, String documentId, Range range) {
    documentReads++;
    return delegate.listByDocument(space, access, documentId, range);
  }

  @Override
  public List<LinkedUnit> linkedUnits(SpaceId space, AccessScope access, List<String> ids) {
    return delegate.linkedUnits(space, access, ids);
  }

  @Override
  public List<DocumentInfo> getDocuments(SpaceId space, AccessScope access, List<String> ids) {
    return delegate.getDocuments(space, access, ids);
  }
}
