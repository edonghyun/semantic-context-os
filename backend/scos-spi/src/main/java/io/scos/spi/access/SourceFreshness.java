package io.scos.spi.access;

import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** 원천별 마지막 성공 수집 시각. 접근 단계 결과와 무관하게 본문 단계의 성공을 기준으로 한다(B9). */
public interface SourceFreshness {
  /**
   * 요청한 인스턴스 중 성공 run이 있는 것만 마지막 완료 시각으로 반환한다. 빈 입력에는 빈 맵이다. 조회 실패는 런타임 예외로 전파하며, 동기 조회이며 별도 취소 토큰은
   * 받지 않는다.
   */
  Map<SourceInstanceId, Instant> lastSucceeded(Set<SourceInstanceId> instances);
}
