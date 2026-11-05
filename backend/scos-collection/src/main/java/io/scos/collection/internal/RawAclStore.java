package io.scos.collection.internal;

import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** 원본 ACL 저장. 트랜잭션은 호출자가 소유하며 같은 DataSource의 트랜잭션에 참여한다. */
public interface RawAclStore {
  /**
   * 권한 또는 실패 원인의 변경 여부를 반환한다. 비관측은 기존 값을 보존하고 최초에는 fallbackAt의 UNKNOWN을 만든다. 원천·fallback 시각은
   * PostgreSQL과 일치하도록 마이크로초로 내림한다. 오래된 관측은 거부하고 동일 마이크로초의 상충은 좁힌다. 같은 의미의 새 관측도 고수위는 전진한다. 비정규
   * source는 IllegalArgumentException, 저장 실패는 어댑터의 unchecked 예외다.
   */
  boolean observe(SourceInstanceId source, String externalId, RawAcl acl, Instant fallbackAt);

  /** 실제 유닛이 있는 distinct 문서 중 ACL 행이 없는 수. 소유 행과 삭제된 문서는 분모가 아니다. */
  int documentsMissingAcl(SpaceId space, SourceInstanceId source);

  /** 없는 키는 생략한다. 빈 입력은 빈 맵이다. */
  Map<String, RawAcl.Observed> get(SourceInstanceId source, Collection<String> externalIds);

  /** 지정 소스의 키만 삭제한다. 빈 입력과 없는 키는 무동작이다. */
  void delete(SourceInstanceId source, Collection<String> externalIds);
}
