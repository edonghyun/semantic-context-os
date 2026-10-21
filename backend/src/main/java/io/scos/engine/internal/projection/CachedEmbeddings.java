package io.scos.engine.internal.projection;

import io.scos.engine.support.text.Text;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.EmbeddingCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 임베딩 입력 한 배치의 벡터를 얻는다(F9, ADR-0026). 캐시에 같은 세대·같은 입력의 벡터가 있으면 그것을 쓰고, 나머지만 임베더에 보낸다 — 배치가 모두 캐시에
 * 있으면 임베더를 부르지 않는다. 차원이 맞지 않는 캐시 벡터는 없는 것으로 본다.
 */
final class CachedEmbeddings {
  /**
   * @param vectors 입력 순서대로의 벡터
   * @param fromCache 그중 캐시에서 얻은 수
   */
  record Batch(List<double[]> vectors, int fromCache) {}

  private final Embedder embedder;
  private final EmbeddingCache cache;
  private final String generation;

  /**
   * @param generation 캐시 키 공간인 임베딩 세대({@link DocumentIngestor#embeddingGeneration(int)})
   */
  CachedEmbeddings(Embedder embedder, EmbeddingCache cache, String generation) {
    this.embedder = embedder;
    this.cache = cache;
    this.generation = generation;
  }

  /** 캐시 키: 임베딩 입력 텍스트의 sha256(UTF-8) 16진수. 키를 만드는 쪽(엔진)이 정한다 — 캐시 구현은 불투명한 문자열로 다룬다. */
  static String keyOf(String embeddingInput) {
    return Text.sha256Hex(embeddingInput);
  }

  Batch vectorsOf(List<String> inputs) {
    Map<String, double[]> cached =
        cache.find(generation, inputs.stream().map(CachedEmbeddings::keyOf).toList());
    List<String> misses = new ArrayList<>();
    for (String input : inputs) if (!usable(cached.get(keyOf(input)))) misses.add(input);
    Map<String, double[]> embedded = new HashMap<>();
    if (!misses.isEmpty()) {
      List<double[]> vecs = embedder.embed(misses);
      if (vecs.size() != misses.size())
        throw new Embedder.EmbedderException(
            "embedding count mismatch: " + vecs.size() + " != " + misses.size());
      for (int i = 0; i < misses.size(); i++) embedded.put(misses.get(i), vecs.get(i));
    }
    List<double[]> vectors = new ArrayList<>(inputs.size());
    for (String input : inputs) {
      double[] hit = cached.get(keyOf(input));
      vectors.add(usable(hit) ? hit : embedded.get(input));
    }
    return new Batch(List.copyOf(vectors), inputs.size() - misses.size());
  }

  private boolean usable(double[] cached) {
    return cached != null && cached.length == embedder.dim();
  }
}
