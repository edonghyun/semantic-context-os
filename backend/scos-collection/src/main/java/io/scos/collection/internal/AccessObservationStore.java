package io.scos.collection.internal;

import io.scos.spi.connector.AclPage;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;

/** 본문과 독립된 run별 그룹·신원 관측 및 전체성 확정 경계. */
public interface AccessObservationStore {
  /** 호출자 트랜잭션에 참여한다. 빈 페이지는 무변경이며 동시각 상충은 제한하여 보관한다. */
  void stage(RunId run, AclPage page);

  /**
   * 자체 트랜잭션에서 첫 저장 전 guard를 실행하고 실제 적용 모드를 반환한다. COMPLETE 신원 누락은 롤백 뒤 별도 INCOMPLETE 트랜잭션으로 재시도한다.
   * 참조 위반 그룹은 위반 참조를 버리고 교집합만 적용하며 INCOMPLETE를 반환한다. 다른 정상 그룹은 요청한 모드대로 적용한다. 저장 오류와 guard 예외는 롤백 후
   * 전파하며 관측은 유지한다. 빈 COMPLETE는 기존 그룹을 비운다.
   */
  AccessMerge merge(
      RunId run, SourceInstanceId source, AccessMerge requested, Instant at, Runnable guard);

  /** 해당 run의 보관값과 상충 표식만 지운다. 진행 중인 병합과 직렬화한다. 빈 run은 무변경이다. */
  void discard(RunId run);
}
