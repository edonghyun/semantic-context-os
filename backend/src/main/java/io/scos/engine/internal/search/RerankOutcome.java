package io.scos.engine.internal.search;

import java.util.List;

/** 재정렬 결과(ADR-0002·DESIGN-PATTERNS §4: 폴백을 숨기지 않고 분기 결과로 센다). Applied만 순서를 바꾼다; 나머지는 원 순서 유지. */
public sealed interface RerankOutcome {
  /** LLM 선택이 유효해 상위 k의 순서를 바꿨다. order는 head 인덱스 순열. */
  record Applied(List<Integer> order, boolean fromCache) implements RerankOutcome {}

  /** 재정렬을 시도하지 않음(비활성·후보 2개 미만). */
  record Skipped(String reason) implements RerankOutcome {}

  /** 공급자 호출 실패 → 원 순서(TS: generator 예외를 삼키고 null). 하네스는 미스 로그로 별도 감지. */
  record FallbackError(String message) implements RerankOutcome {}

  /** 응답 형식이 유효한 선택이 아님 → 원 순서. */
  record FallbackFormat(String response) implements RerankOutcome {}
}
