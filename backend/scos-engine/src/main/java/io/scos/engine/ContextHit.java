package io.scos.engine;

import io.scos.spi.store.Tier;

/** 검색 결과 한 건. score는 레시피 점수 그대로다(반올림은 MCP 와이어 표현). */
public record ContextHit(
    String unitId,
    String documentId,
    String title,
    String content,
    String uri,
    String sourceId,
    String capturedAt,
    Tier tier,
    double score) {}
