package io.scos.spi.embedding;

import java.util.List;
import java.util.OptionalInt;

/**
 * 임베딩 공급자 포트(외부 경계). 구현: Gemini HTTP, OpenAI 호환 HTTP, 하네스 카세트(ADR-0017). 실패는 {@link
 * EmbedderException}.
 */
public interface Embedder {
  /** 공급자·모델·차원(stage2 §2.2) — 임베딩 세대의 재료. */
  EmbedderIdentity identity();

  /** 벡터 차원 = {@code identity().dim()}. */
  default int dim() {
    return identity().dim();
  }

  /** texts[i] → vectors[i]. 비어 있으면 빈 목록. */
  List<double[]> embed(List<String> texts);

  /** 임베딩 실패. HTTP 상태(있으면)·원인을 들고 다녀 투영이 예외 타입·필드로 분류한다(메시지 정규식 금지, DESIGN-PATTERNS §13). */
  final class EmbedderException extends RuntimeException {
    private final OptionalInt status;

    public EmbedderException(String message) {
      super(message);
      this.status = OptionalInt.empty();
    }

    public EmbedderException(String message, int httpStatus) {
      super(message);
      this.status = OptionalInt.of(httpStatus);
    }

    public EmbedderException(String message, Throwable cause) {
      super(message, cause);
      this.status = OptionalInt.empty();
    }

    public OptionalInt status() {
      return status;
    }
  }
}
