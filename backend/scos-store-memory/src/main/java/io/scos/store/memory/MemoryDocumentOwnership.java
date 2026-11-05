package io.scos.store.memory;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceOwnershipException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 유닛·ACL이 공유하는 문서 소유 상태. 배치는 모든 소유를 검증한 뒤 한 번에 반영한다. */
public final class MemoryDocumentOwnership implements DocumentOwnership {
  private final Map<String, Owner> owners = new LinkedHashMap<>();

  private record Owner(SpaceId space, String sourceId) {
    Claim check(SpaceId attemptedSpace, String attemptedSource, String documentId) {
      if (!space.equals(attemptedSpace))
        throw new SpaceOwnershipException(documentId, space, attemptedSpace);
      return sourceId.equals(attemptedSource) ? Claim.OWNED : Claim.OTHER_SOURCE;
    }
  }

  @Override
  public synchronized Claim claim(SpaceId space, String sourceId, String documentId) {
    return owners
        .computeIfAbsent(documentId, ignored -> new Owner(space, sourceId))
        .check(space, sourceId, documentId);
  }

  synchronized void claim(SpaceId space, List<ContextUnit> units) {
    var pending = new LinkedHashMap<>(owners);
    for (ContextUnit unit : units) {
      String sourceId = unit.provenance().sourceId();
      Owner owner =
          pending.computeIfAbsent(unit.documentId(), ignored -> new Owner(space, sourceId));
      if (owner.check(space, sourceId, unit.documentId()) == Claim.OTHER_SOURCE)
        throw new IllegalArgumentException("다른 출처 소유 문서의 쓰기 거부: " + unit.documentId());
    }
    owners.putAll(pending);
  }

  /** 선점·저장 변경 없이 해당 공간과 출처가 소유를 얻거나 유지할 수 있는지 확인한다. 없는 문서는 true다. */
  public synchronized boolean canClaim(SpaceId space, String sourceId, String documentId) {
    Owner owner = owners.get(documentId);
    return owner == null || (owner.space().equals(space) && owner.sourceId().equals(sourceId));
  }

  @Override
  public synchronized Optional<SpaceId> ownedSpace(String sourceId, String documentId) {
    Owner owner = owners.get(documentId);
    return owner != null && owner.sourceId().equals(sourceId)
        ? Optional.of(owner.space())
        : Optional.empty();
  }

  @Override
  public synchronized Set<String> listDocumentIds(SpaceId space, String sourceId) {
    return owners.entrySet().stream()
        .filter(entry -> entry.getValue().equals(new Owner(space, sourceId)))
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }
}
