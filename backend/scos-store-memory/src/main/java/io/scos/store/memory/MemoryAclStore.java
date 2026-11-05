package io.scos.store.memory;

import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.store.SpaceId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 메모리 문서 ACL 저장소. */
public final class MemoryAclStore implements AclStore {
  private final Map<DocumentKey, DocumentAcl> values = new ConcurrentHashMap<>();

  @Override
  public boolean hasAny() {
    return !values.isEmpty();
  }

  @Override
  public void put(SpaceId space, List<DocumentAcl> acls) {
    for (DocumentAcl acl : acls)
      values.compute(
          new DocumentKey(space, acl.documentId()),
          (key, previous) ->
              previous != null
                      && ((previous.fingerprint().equals(acl.fingerprint())
                              && previous.reason() == acl.reason())
                          || !acl.observedAt().isAfter(previous.observedAt()))
                  ? previous
                  : acl);
  }

  @Override
  public Map<String, DocumentAcl> get(SpaceId space, Collection<String> documentIds) {
    return documentIds.stream()
        .distinct()
        .map(id -> values.get(new DocumentKey(space, id)))
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableMap(DocumentAcl::documentId, acl -> acl));
  }

  @Override
  public void delete(SpaceId space, Collection<String> documentIds) {
    for (String id : documentIds) values.remove(new DocumentKey(space, id));
  }

  private record DocumentKey(SpaceId space, String documentId) {}
}
