package io.scos.engine;

/** 재정렬 결과. 폴백은 예외가 아니라 이 값으로 알리고 원래 순서를 유지한다. LLM 응답 원문은 싣지 않는다. */
public record RerankStatus(Kind kind, boolean fromCache) {
  public enum Kind {
    APPLIED,
    SKIPPED,
    FALLBACK_PROVIDER_ERROR,
    FALLBACK_INVALID_FORMAT
  }
}
