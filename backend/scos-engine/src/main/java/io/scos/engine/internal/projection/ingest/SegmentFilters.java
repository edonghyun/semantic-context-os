package io.scos.engine.internal.projection.ingest;

import io.scos.engine.support.text.Text;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 세그먼트 단계 필터 셋(TS partition-router.ts·min-token-filter.ts·dedup-filter.ts). 각각 한 규칙. */
final class SegmentFilters {
  static final List<String> VALID_PARTITIONS =
      List.of("/knowledge", "/rules", "/environment", "/memory");
  static final int MIN_TOKENS = 10;

  private SegmentFilters() {}

  /** Content-Based Router — 요청된 파티션이 유효하면 반영, 아니면 /knowledge. */
  static List<Segment> routePartition(List<Segment> segments) {
    return segments.stream()
        .map(
            s ->
                s.withPartition(
                    s.requestedPartition().filter(VALID_PARTITIONS::contains).orElse("/knowledge")))
        .toList();
  }

  /** 검색 가치가 없는 초소형 세그먼트(10 토큰 미만)를 걸러낸다. */
  static List<Segment> dropTiny(List<Segment> segments) {
    return segments.stream().filter(s -> s.tokens() >= MIN_TOKENS).toList();
  }

  /** 동일 내용(sha256) 세그먼트 중 첫 번째만 남긴다. */
  static List<Segment> dedup(List<Segment> segments) {
    Set<String> seen = new HashSet<>();
    List<Segment> out = new ArrayList<>();
    for (Segment s : segments) if (seen.add(Text.sha256Hex(s.content()))) out.add(s);
    return out;
  }
}
