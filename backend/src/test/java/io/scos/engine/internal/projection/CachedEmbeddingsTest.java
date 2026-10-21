package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.EmbeddingCache;
import io.scos.store.memory.MemoryEmbeddingCache;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 임베딩 입력 한 배치의 벡터(F9, ADR-0026): 캐시에 있으면 캐시 벡터, 없으면 그 입력의 임베더 벡터. 입력마다 다른 벡터를 내는 임베더로 본다. */
@DisplayName("임베딩 배치는 캐시에 있으면 캐시 벡터, 없으면 임베더 벡터를 받는다(ADR-0026)")
class CachedEmbeddingsTest {
  static final String GENERATION = DocumentIngestor.embeddingGeneration(4);
  static final double[] CACHED = {9, 8, 7, 6};
  static final double[] SHORT = {9, 8, 7};

  /** 캐시에 있는 입력 둘과 없는 입력 둘이 번갈아 온다. */
  static final List<String> MIXED = List.of("hit-1", "miss-1", "hit-2", "miss-2");

  final RecordingEmbedder embedder = new RecordingEmbedder();
  final MemoryEmbeddingCache cache = new MemoryEmbeddingCache();
  final CachedEmbeddings embeddings = new CachedEmbeddings(embedder, cache, GENERATION);

  void givenCached(double[] vector, String... inputs) {
    for (String input : inputs)
      cache.putIfAbsent(GENERATION, Map.of(CachedEmbeddings.keyOf(input), vector));
  }

  @Nested
  @DisplayName("hit-1·hit-2가 캐시에 있을 때 적중과 미적중이 섞인 배치를 읽으면")
  class MixedBatch {
    @BeforeEach
    void givenHitsCached() {
      givenCached(CACHED, "hit-1", "hit-2");
    }

    @Test
    @DisplayName("입력마다 캐시 벡터나 자기 입력의 임베더 벡터를 받는다")
    void eachInputGetsItsOwnVector() {
      // when
      CachedEmbeddings.Batch batch = embeddings.vectorsOf(MIXED);

      // then
      assertThat(batch.vectors())
          .containsExactly(
              CACHED,
              RecordingEmbedder.vectorOf("miss-1"),
              CACHED,
              RecordingEmbedder.vectorOf("miss-2"));
    }

    @Test
    @DisplayName("미적중 입력만 순서대로 임베더에 보낸다")
    void embedsOnlyMisses() {
      // when
      embeddings.vectorsOf(MIXED);

      // then
      assertThat(embedder.calls).containsExactly("miss-1", "miss-2");
    }

    @Test
    @DisplayName("캐시에서 얻은 입력 수를 센다")
    void countsCacheHits() {
      // when
      CachedEmbeddings.Batch batch = embeddings.vectorsOf(MIXED);

      // then
      assertThat(batch.fromCache()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("모든 입력이 캐시에 있으면 임베더를 부르지 않는다")
  void allHitsSkipEmbedder() {
    // given
    givenCached(CACHED, "hit-1", "hit-2");

    // when
    embeddings.vectorsOf(List.of("hit-1", "hit-2"));

    // then
    assertThat(embedder.calls).isEmpty();
  }

  @Test
  @DisplayName("차원이 다른 캐시 벡터는 미적중으로 보고 임베딩한다")
  void otherDimensionIsMiss() {
    // given
    givenCached(SHORT, "hit-1");

    // when
    CachedEmbeddings.Batch batch = embeddings.vectorsOf(List.of("hit-1"));

    // then
    assertThat(batch.vectors()).containsExactly(RecordingEmbedder.vectorOf("hit-1"));
  }

  @Test
  @DisplayName("캐시가 없으면 입력마다 자기 입력의 임베더 벡터를 받는다")
  void withoutCacheEachInputGetsItsOwnVector() {
    // given
    CachedEmbeddings uncached = new CachedEmbeddings(embedder, EmbeddingCache.NONE, GENERATION);

    // when
    CachedEmbeddings.Batch batch = uncached.vectorsOf(MIXED);

    // then
    assertThat(batch.vectors())
        .containsExactlyElementsOf(MIXED.stream().map(RecordingEmbedder::vectorOf).toList());
  }

  @Test
  @DisplayName("임베더가 돌려준 벡터 수가 미적중 수와 다르면 실패한다")
  void countMismatchFails() {
    // given: 미적중 넷에 벡터 하나만 돌려주는 임베더
    Embedder short1 =
        new Embedder() {
          @Override
          public int dim() {
            return 4;
          }

          @Override
          public List<double[]> embed(List<String> texts) {
            return List.of(CACHED);
          }
        };

    // when
    Throwable failed =
        catchThrowable(
            () -> new CachedEmbeddings(short1, EmbeddingCache.NONE, GENERATION).vectorsOf(MIXED));

    // then
    assertThat(failed)
        .isInstanceOf(Embedder.EmbedderException.class)
        .hasMessage("embedding count mismatch: 1 != 4");
  }
}
