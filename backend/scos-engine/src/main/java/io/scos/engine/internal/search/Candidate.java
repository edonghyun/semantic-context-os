package io.scos.engine.internal.search;

import io.scos.spi.store.ContextUnit;

/** 검색 후보: 유닛과 단계마다 바뀌는 기본 점수. */
public record Candidate(ContextUnit unit, double baseScore) {}
