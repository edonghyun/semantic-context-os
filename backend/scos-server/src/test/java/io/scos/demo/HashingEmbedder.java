package io.scos.demo;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import java.util.List;

/**
 * 데모의 키 없는 임베더(demo/embedder/hashing_embedder.py)와 같은 글자 bigram 해싱 벡터를 프로세스 안에서 낸다 — {@link
 * DemoProviders#hashingVector}. 철자 겹침만 반영한다(의미 임베딩 아님). identity는 데모 compose가 서버에 주는 OpenAI 호환 설정
 * (공급자·모델·차원)과 같다 — 데모 4단계의 세대와 같은 세대로 적재된다. 두 벡터가 같음은 HashingEmbedderParityTest가 고정한다.
 */
final class HashingEmbedder implements Embedder {
  static final String PROVIDER = "openai-compatible";
  static final String MODEL = "scos-demo-hashing";

  @Override
  public EmbedderIdentity identity() {
    return new EmbedderIdentity(PROVIDER, MODEL, DemoProviders.DIM);
  }

  @Override
  public List<double[]> embed(List<String> texts) {
    return texts.stream().map(DemoProviders::hashingVector).toList();
  }
}
