package io.scos.engine.internal.projection.ingest;

import io.scos.engine.internal.projection.JtokkitTokenizer;
import io.scos.spi.projection.SourceDocument;
import java.util.List;

/** 실제 o200k 토크나이저와 유효한 문서 봉투를 연결해 분할 결과를 관찰한다. */
final class HeadingScenario {
  private static final String CAPTURED_AT = "2026-10-28T00:00:00Z";
  private final HeadingSegmenter segmenter;

  HeadingScenario(int maxTokens) {
    segmenter = new HeadingSegmenter(new JtokkitTokenizer(), maxTokens);
  }

  List<Segment> split(String body) {
    return segmenter.apply(
        new SourceDocument(
            "notion",
            "doc",
            "https://example.test/docs/doc",
            "문서",
            body,
            CAPTURED_AT,
            CAPTURED_AT));
  }
}
