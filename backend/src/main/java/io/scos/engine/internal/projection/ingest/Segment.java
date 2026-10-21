package io.scos.engine.internal.projection.ingest;

import java.util.List;
import java.util.Optional;

/** 파이프라인 중간 메시지(TS segment.ts): 헤딩 단위 조각. tokens는 분할기가 측정한 값. */
record Segment(
    String headingPath,
    String title,
    String content,
    int tokens,
    List<String> tags,
    String partition,
    Optional<String> requestedPartition) {
  Segment withPartition(String p) {
    return new Segment(headingPath, title, content, tokens, tags, p, requestedPartition);
  }
}
