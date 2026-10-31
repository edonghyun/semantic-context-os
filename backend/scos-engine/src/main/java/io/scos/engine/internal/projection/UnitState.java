package io.scos.engine.internal.projection;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Tier;
import java.util.List;
import java.util.Optional;

/** 투영·재임베딩에서 원본 필드를 유지한 채 유닛의 생애 상태를 바꾼다. */
final class UnitState {
  private UnitState() {}

  /** 임베딩만 바꾼 유닛 — 재임베딩({@link SpaceReembedding})도 쓴다. */
  static ContextUnit withEmbedding(ContextUnit u, Optional<double[]> embedding) {
    return withLifecycle(u, u.tier(), u.invalidatedAt(), embedding, u.compactedFrom());
  }

  /** 원본에서 온 필드는 u 그대로, 생애 필드(보관·무효화·임베딩·회수 근거)만 이것으로 바꾼 유닛. 16필드 복사는 여기 한 곳이다. */
  static ContextUnit withLifecycle(
      ContextUnit u,
      Tier tier,
      Optional<String> invalidatedAt,
      Optional<double[]> embedding,
      List<String> compactedFrom) {
    return new ContextUnit(
        u.id(),
        u.partition(),
        u.title(),
        u.content(),
        u.contentHash(),
        u.tokens(),
        tier,
        u.provenance(),
        u.importance(),
        u.validFrom(),
        invalidatedAt,
        u.links(),
        u.tags(),
        u.aliases(),
        embedding,
        compactedFrom);
  }
}
