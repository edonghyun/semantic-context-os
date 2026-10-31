package io.scos.mcp.wire;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * MCP 응답 DTO(리뷰 M-6). 컴포넌트 선언 순서 = 와이어 응답 키 순서(Jackson이 record를 선언 순서로 직렬화한다 — 순서를 바꾸면 클라이언트가 보는
 * 출력이 바뀐다). null은 그대로 싣는다({@code nextOffset: null}, {@code latestCapturedAt: null}).
 */
public final class Wire {
  private Wire() {}

  public record FreshnessView(
      @Nullable String latestCapturedAt, @Nullable Double staleHours, boolean stale) {}

  public record SearchHit(
      String title,
      String excerpt,
      String source,
      String documentId,
      String capturedAt,
      double score) {}

  /** 적용된 검색 옵션 — 소문자 이름(effort·profile)과 상향 여부. */
  public record Applied(String effort, String profile, boolean escalated) {}

  public record SearchResult(List<SearchHit> hits, FreshnessView freshness, Applied applied) {}

  public record UnitView(String title, String content) {}

  public record ReadPage(
      String title,
      String source,
      String uri,
      List<UnitView> units,
      @Nullable Integer nextOffset) {}

  public record RelatedItem(
      String documentId, String title, String source, double score, String via) {}

  public record RelatedResult(List<RelatedItem> related) {}

  public record AssembleUnit(
      String title, String source, String documentId, String capturedAt, String content) {}

  /** 응답 키 순서:units·tokensUsed·truncated·freshness·applied. */
  public record AssembleResult(
      List<AssembleUnit> units,
      int tokensUsed,
      boolean truncated,
      FreshnessView freshness,
      Applied applied) {}
}
