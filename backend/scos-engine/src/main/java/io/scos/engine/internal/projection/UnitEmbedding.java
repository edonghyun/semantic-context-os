package io.scos.engine.internal.projection;

import io.scos.engine.support.projection.EmbeddingCounts;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.ContextUnit;
import java.util.List;

/** 투영이 유닛에 벡터를 붙이는 방식. 임베더로 붙이거나, 키워드만 쓰는 조립이면 붙이지 않는다. */
public interface UnitEmbedding {
  /** sync 하나에서 완료한 배치를 센다. 뒤 배치가 실패해도 앞 배치의 작업량은 보고에 남는다. */
  final class Tally {
    private int fromCache;
    private int embedded;

    void recordBatch(int cached, int generated) {
      fromCache += cached;
      embedded += generated;
    }

    EmbeddingCounts counts() {
      return new EmbeddingCounts(fromCache, embedded);
    }
  }

  /** 이 방식으로 만드는 벡터의 세대 — 공간의 {@code embedding:version}과 캐시 키 공간이다. */
  EmbeddingGeneration generation();

  /** units에 벡터를 붙이고(순서 유지) 완료한 배치마다 작업량을 tally에 남긴다. */
  List<ContextUnit> attach(List<ContextUnit> units, Tally tally);
}
