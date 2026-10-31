package io.scos.engine;

import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.SpaceId;

/**
 * 공간에 저장된 임베딩 세대가 설정 세대와 다르거나 재임베딩이 끝나지 않았다(stage2 §3.3). 기동·투영 쓰기를 거부하고 {@code scos reembed}를 안내한다
 * — 세대가 섞인 벡터로 검색이 조용히 틀리는 대신. 다시 임베딩은 명시 호출로만 한다(자동 재임베딩 없음).
 */
public final class EmbeddingGenerationMismatchException extends IllegalStateException {
  private final SpaceId space;
  private final String stored;
  private final EmbeddingGeneration configured;
  private final boolean reembedPending;

  private EmbeddingGenerationMismatchException(
      String message,
      SpaceId space,
      String stored,
      EmbeddingGeneration configured,
      boolean reembedPending) {
    super(message);
    this.space = space;
    this.stored = stored;
    this.configured = configured;
    this.reembedPending = reembedPending;
  }

  /** 저장된 세대가 설정과 다르다. */
  static EmbeddingGenerationMismatchException differs(
      SpaceId space, String stored, EmbeddingGeneration configured) {
    return new EmbeddingGenerationMismatchException(
        "space "
            + space.value()
            + ": stored embedding generation "
            + stored
            + ", configured "
            + configured.value()
            + ". Run 'scos reembed --space="
            + space.value()
            + "' or restore SCOS_EMBEDDER_* settings.",
        space,
        stored,
        configured,
        false);
  }

  /** 재임베딩이 pending 표시만 남기고 끝나지 않았다 — 설정과 같은 목표여도 섞인 벡터다. */
  static EmbeddingGenerationMismatchException pending(
      SpaceId space, String stored, EmbeddingGeneration configured) {
    return new EmbeddingGenerationMismatchException(
        "space "
            + space.value()
            + ": re-embedding to "
            + configured.value()
            + " did not finish. Re-run 'scos reembed --space="
            + space.value()
            + "'.",
        space,
        stored,
        configured,
        true);
  }

  public SpaceId space() {
    return space;
  }

  /** 공간에 저장된 값 그대로({@code pending:} 표시 포함). */
  public String stored() {
    return stored;
  }

  public EmbeddingGeneration configured() {
    return configured;
  }

  /** 끝나지 않은 재임베딩({@code pending:<목표>})이 남아 있는가. */
  public boolean reembedPending() {
    return reembedPending;
  }
}
