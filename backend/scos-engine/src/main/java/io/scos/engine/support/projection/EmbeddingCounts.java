package io.scos.engine.support.projection;

/** 이번 sync에서 벡터를 얻은 입력 수: 임베딩 캐시에서(fromCache, ADR-0026) / 임베더 호출로(embedded). 재수집의 캐시 적중률을 본다. */
public record EmbeddingCounts(int fromCache, int embedded) {}
