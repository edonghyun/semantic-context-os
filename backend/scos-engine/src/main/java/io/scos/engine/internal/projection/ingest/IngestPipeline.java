package io.scos.engine.internal.projection.ingest;

import io.scos.spi.projection.SourceDocument;
import io.scos.spi.projection.Tokenizer;
import io.scos.spi.store.ContextUnit;
import java.util.List;

/**
 * 인제스천 기본 합성(TS core/ingestion/ingest-pipeline.ts, G3): 정규화 → 헤딩 분할 → 파티션 라우팅 → 10 토큰 미만 제거 → 동일 내용
 * 제거 → 유닛 완성. 결정론: 같은 문서는 같은 유닛(id·내용·토큰)을 낸다(F-3·F-6).
 */
public final class IngestPipeline {
  private final BodyNormalizer normalizer = new BodyNormalizer();
  private final HeadingSegmenter segmenter;
  private final UnitEnricher enricher = new UnitEnricher();

  public IngestPipeline(Tokenizer tokenizer) {
    this.segmenter = new HeadingSegmenter(tokenizer, HeadingSegmenter.DEFAULT_MAX_TOKENS);
  }

  public List<ContextUnit> apply(SourceDocument doc) {
    SourceDocument normalized = normalizer.apply(doc);
    List<Segment> segments = segmenter.apply(normalized);
    segments = SegmentFilters.routePartition(segments);
    segments = SegmentFilters.dropTiny(segments);
    segments = SegmentFilters.dedup(segments);
    return enricher.apply(segments, normalized);
  }
}
