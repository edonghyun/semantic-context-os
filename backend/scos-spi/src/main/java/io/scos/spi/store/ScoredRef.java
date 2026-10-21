package io.scos.spi.store;

/** 색인 결과 한 건 — 유닛 id·문서 id·어댑터가 매긴 점수(CONTRACTS B 계약의 `ScoredRef`). */
public record ScoredRef(String unitId, String documentId, double score) {}
