package io.scos.spi.embedding;

import java.util.List;
import java.util.function.Function;

/** 고정 응답 임베더(TESTING §3.2·§4): 결정론 벡터를 돌려준다. 실 호출 없음. */
public final class FixedEmbedder implements Embedder {
  private final int dim;
  private final Function<String, double[]> vectorOf;

  private FixedEmbedder(int dim, Function<String, double[]> vectorOf) {
    this.dim = dim;
    this.vectorOf = vectorOf;
  }

  /** 영벡터 — 벡터 채널 결과 없음(키워드 전용 사례용; 임베더는 필수라 null로 대신할 수 없다). */
  public static FixedEmbedder zero(int dim) {
    return new FixedEmbedder(dim, t -> new double[dim]);
  }

  /** 텍스트에 keyword가 있으면 match, 아니면 otherwise. */
  public static FixedEmbedder byKeyword(String keyword, double[] match, double[] otherwise) {
    if (match.length != otherwise.length)
      throw new IllegalArgumentException(
          "vector dims differ: " + match.length + " != " + otherwise.length);
    return new FixedEmbedder(
        match.length, t -> t.contains(keyword) ? match.clone() : otherwise.clone());
  }

  @Override
  public int dim() {
    return dim;
  }

  @Override
  public List<double[]> embed(List<String> texts) {
    return texts.stream().map(vectorOf).toList();
  }
}
