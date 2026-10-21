package io.scos.demo;

import io.scos.spi.embedding.Embedder;
import java.util.List;

/**
 * 데모의 키 없는 임베더(demo/embedder/hashing_embedder.py)와 같은 글자 bigram 해싱 벡터를 프로세스 안에서 낸다 — {@link
 * DemoProviders#hashingVector}. 철자 겹침만 반영한다(의미 임베딩 아님).
 */
final class HashingEmbedder implements Embedder {
  @Override
  public int dim() {
    return DemoProviders.DIM;
  }

  @Override
  public List<double[]> embed(List<String> texts) {
    return texts.stream().map(DemoProviders::hashingVector).toList();
  }
}
