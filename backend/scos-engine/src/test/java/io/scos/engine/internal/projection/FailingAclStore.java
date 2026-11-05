package io.scos.engine.internal.projection;

import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.store.SpaceId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 실제 저장소의 조회·삭제를 유지하며 ACL put 장애만 주입한다. */
final class FailingAclStore implements AclStore {
  private final AclStore delegate;

  FailingAclStore(AclStore delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean hasAny() {
    return delegate.hasAny();
  }

  @Override
  public void put(SpaceId space, List<DocumentAcl> acls) {
    throw new IllegalStateException("injected ACL put failure");
  }

  @Override
  public Map<String, DocumentAcl> get(SpaceId space, Collection<String> ids) {
    return delegate.get(space, ids);
  }

  @Override
  public void delete(SpaceId space, Collection<String> ids) {
    delegate.delete(space, ids);
  }
}
