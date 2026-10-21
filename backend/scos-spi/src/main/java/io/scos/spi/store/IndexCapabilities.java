package io.scos.spi.store;

/**
 * 어댑터 능력 선언(ADR-0014). 계약 테스트가 사례 판정에 쓴다.
 *
 * @param consistency TRANSACTIONAL = 호출자 트랜잭션과 함께 갱신되어 커밋 즉시 보임, EVENTUAL = Outbox 경로로 지연 반영
 * @param approximate true면 근사 검색이라 recall 하한만 약속한다
 */
public record IndexCapabilities(Consistency consistency, boolean approximate) {
  public enum Consistency {
    TRANSACTIONAL,
    EVENTUAL
  }

  public static IndexCapabilities exactTransactional() {
    return new IndexCapabilities(Consistency.TRANSACTIONAL, false);
  }
}
