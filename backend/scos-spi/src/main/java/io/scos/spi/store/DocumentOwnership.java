package io.scos.spi.store;

import java.util.Optional;
import java.util.Set;

/** 문서의 첫 소유 공간·출처를 유닛과 ACL 쓰기가 공유한다(A2·A3). */
public interface DocumentOwnership {
  enum Claim {
    OWNED,
    OTHER_SOURCE
  }

  /**
   * 최초 소유를 등록하거나 현재 소유를 확인한다. 다른 공간은 SpaceOwnershipException, 같은 공간의 다른 출처는 OTHER_SOURCE이며 기존 소유를
   * 바꾸지 않는다. DB 구현은 호출자 트랜잭션에 참여하고 소유 행을 마감까지 잠근다.
   */
  Claim claim(SpaceId space, String sourceId, String documentId);

  /** 현재 소유 출처가 일치하는 문서의 실제 공간. 없거나 다른 출처면 empty이며 선점하지 않는다. */
  Optional<SpaceId> ownedSpace(String sourceId, String documentId);

  /** 지정 공간·출처의 소유 문서 집합. 유닛 없는 선점도 포함하며 없으면 빈 집합이다. */
  Set<String> listDocumentIds(SpaceId space, String sourceId);
}
