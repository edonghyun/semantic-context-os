package io.scos.spi.embedding;

import java.util.regex.Pattern;

/**
 * 임베더 identity(stage2 §2.2): 공급자·모델·차원. 임베딩 세대의 재료라 세대 문자열에 섞일 수 없는 값은 만들 때 거부한다 — 공급자는 {@code
 * [a-z0-9][a-z0-9-]*}, 모델은 비어 있지 않고 공백이 없으며, 차원은 1 이상이다.
 */
public record EmbedderIdentity(String provider, String model, int dim) {
  private static final Pattern PROVIDER = Pattern.compile("[a-z0-9][a-z0-9-]*");
  private static final Pattern NO_WHITESPACE = Pattern.compile("\\S+");

  public EmbedderIdentity {
    if (provider == null || !PROVIDER.matcher(provider).matches())
      throw new IllegalArgumentException("embedder provider must match [a-z0-9][a-z0-9-]*");
    if (model == null || !NO_WHITESPACE.matcher(model).matches())
      throw new IllegalArgumentException("embedder model must be non-empty without whitespace");
    if (dim < 1) throw new IllegalArgumentException("embedder dim must be >= 1: " + dim);
  }
}
