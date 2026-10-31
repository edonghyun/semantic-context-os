package io.scos.spi.access;

import io.scos.spi.store.SpaceId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 공간별 문서 ACL 저장. 저장 장애는 구현의 비검사 예외로 전달하며 호출자 트랜잭션에 참여한다. 취소는 호출 스레드의 인터럽트로 전달한다. */
public interface AclStore {
  /** 모든 공간·상태를 대상으로 행이 하나라도 있는가. 비어 있으면 false. */
  boolean hasAny();

  /** fingerprint가 다르고 observedAt이 저장값보다 새로운 ACL만 바꾼다. 빈 목록은 변경 없음. */
  void put(SpaceId space, List<DocumentAcl> acls);

  /** 요청한 문서 중 저장된 ACL만 돌려준다. 없는 문서와 빈 입력은 결과에서 빠진다. */
  Map<String, DocumentAcl> get(SpaceId space, Collection<String> documentIds);

  /** 요청한 문서 ACL만 지운다. 없는 문서·빈 입력은 변경 없음. */
  void delete(SpaceId space, Collection<String> documentIds);
}
