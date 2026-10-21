package io.scos.mcp.wire;

import java.util.List;

/**
 * MCP 응답 DTO(리뷰 M-6). 컴포넌트 선언 순서 = TS 응답 키 순서(Jackson이 record를 선언 순서로 직렬화한다). null은 TS처럼 그대로 싣는다
 * ({@code nextOffset: null}, {@code latestCapturedAt: null}).
 */
public final class Wire {
  private Wire() {}

  public record FreshnessView(String latestCapturedAt, Double staleHours, boolean stale) {}

  public record SearchHit(
      String title,
      String excerpt,
      String source,
      String documentId,
      String capturedAt,
      double score) {}

  public record SearchResult(List<SearchHit> hits, FreshnessView freshness) {}

  public record UnitView(String title, String content) {}

  public record ReadPage(
      String title, String source, String uri, List<UnitView> units, Integer nextOffset) {}

  public record RelatedItem(
      String documentId, String title, String source, double score, String via) {}

  public record RelatedResult(List<RelatedItem> related) {}

  public record AssembleUnit(String title, String source, String documentId, String content) {}

  /** TS 응답 키 순서: units·tokensUsed·truncated·freshness. */
  public record AssembleResult(
      List<AssembleUnit> units, int tokensUsed, boolean truncated, FreshnessView freshness) {}
}
