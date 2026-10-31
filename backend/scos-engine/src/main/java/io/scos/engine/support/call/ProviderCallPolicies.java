package io.scos.engine.support.call;

import java.time.Duration;
import java.util.Optional;

/**
 * 실제로 조립에 넘긴 공급자 호출 정책(공개 배포 단계 5 T3): 임베딩 하나, 재정렬은 켜져 있을 때만. 임베더·생성기의 실행기는 이 값으로만 만든다 — 정책이 다른 경로로
 * 새지 않게. 시도 timeout은 역할마다 그 공급자 설정에서 온다.
 */
public record ProviderCallPolicies(
    ProviderCallPolicy embedding, Optional<ProviderCallPolicy> rerank) {
  /** 대화형(검색)이면 1회 시도·총 예산 = 시도 timeout, 아니면(수집·투영) 3회·총 2분. 재정렬 timeout이 없으면 재정렬 정책도 없다. */
  public static ProviderCallPolicies of(
      boolean interactive, Duration embeddingTimeout, Optional<Duration> rerankTimeout) {
    ProviderCallMode mode = interactive ? ProviderCallMode.INTERACTIVE : ProviderCallMode.BATCH;
    return new ProviderCallPolicies(mode.policy(embeddingTimeout), rerankTimeout.map(mode::policy));
  }

  /**
   * 호출 용도. 대화형(검색: 질의 임베딩·재정렬)은 1회 시도 + timeout — 재시도·Retry-After 대기 없이 실패를 바로 돌려준다(불변식 8, ADR-0020
   * §5). 일괄(수집·투영 명령, 데몬 포함)은 최대 3회·총 2분 — 투영은 자체 재시도(ProjectionRetryPolicy)가 있으므로 F7 기본(5회·600s)보다
   * 짧게.
   */
  private enum ProviderCallMode {
    INTERACTIVE,
    BATCH;

    static final int BATCH_ATTEMPTS = 3;
    static final Duration BATCH_BUDGET = Duration.ofMinutes(2);

    ProviderCallPolicy policy(Duration attemptTimeout) {
      return switch (this) {
        case INTERACTIVE -> new ProviderCallPolicy(attemptTimeout, 1, attemptTimeout, Math::random);
        case BATCH ->
            new ProviderCallPolicy(attemptTimeout, BATCH_ATTEMPTS, BATCH_BUDGET, Math::random);
      };
    }
  }
}
