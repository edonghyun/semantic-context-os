package io.scos.engine;

import io.scos.spi.embedding.Embedder;
import java.util.OptionalInt;

/** 질의 임베딩 실패(B11: 저하 없이 실패한다). cause는 {@link Embedder.EmbedderException}이다. */
public final class EmbeddingFailedException extends ContextEngineException {
  private final OptionalInt httpStatus;

  public EmbeddingFailedException(Embedder.EmbedderException cause) {
    super("query embedding failed: " + cause.getMessage(), cause);
    this.httpStatus = cause.status();
  }

  /** 공급자 HTTP 상태(있으면). */
  public OptionalInt httpStatus() {
    return httpStatus;
  }
}
