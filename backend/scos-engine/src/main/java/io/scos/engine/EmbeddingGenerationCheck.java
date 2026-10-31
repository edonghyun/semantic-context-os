package io.scos.engine;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import java.util.Collection;

/**
 * 엔진의 임베딩 세대(stage2 §3.1·§3.3): 임베더 identity + 엔진이 임베딩 입력을 만드는 형식. 공간의 {@code embedding:version}과
 * 임베딩 캐시의 키 공간이 이 값이다. 공간에 저장된 세대가 설정과 다르거나 재임베딩이 끝나지 않았으면({@code pending:<목표>}) 거부한다 — 자동으로 다시
 * 임베딩하지 않고 {@code scos reembed}를 안내한다. 비교는 문자열 동등이고 저장값을 해석하지 않는다(모르는 라벨도 그대로 거부된다).
 */
public final class EmbeddingGenerationCheck {
  /** 임베딩 입력 형식 — 제목, 별칭, 본문을 줄바꿈으로 잇는다(제목·별칭·본문 순서). */
  public static final String INPUT_FORMAT = "title+aliases+content";

  /** 공간 메타에서 세대를 두는 키. */
  public static final String META_KEY = "embedding:version";

  /** 끝나지 않은 재임베딩의 표시: {@code pending:<목표 세대>}. */
  public static final String PENDING_PREFIX = EmbeddingGeneration.PENDING_PROVIDER + ":";

  private EmbeddingGenerationCheck() {}

  /** 이 임베더로 엔진이 만드는 벡터의 세대. */
  public static EmbeddingGeneration generationOf(Embedder embedder) {
    return EmbeddingGeneration.of(embedder.identity(), INPUT_FORMAT);
  }

  /**
   * 공간마다 저장된 세대를 설정 세대와 대조한다(기동·투영 시작). 값이 없으면(아직 임베딩하지 않은 공간) 통과한다.
   *
   * @throws EmbeddingGenerationMismatchException 어느 공간이든 다르거나 pending이면
   */
  public static void verify(
      SpaceMeta meta, Collection<SpaceId> spaces, EmbeddingGeneration configured) {
    for (SpaceId space : spaces)
      meta.get(space, META_KEY).ifPresent(stored -> requireMatches(space, stored, configured));
  }

  /**
   * 저장된 값 하나를 설정 세대와 대조한다 — 쓰기 트랜잭션이 잠가 읽은 값에 쓴다. pending이면 목표가 설정과 같아도 거부한다(섞인 벡터).
   *
   * @throws EmbeddingGenerationMismatchException 다르거나 pending이면
   */
  public static void requireMatches(SpaceId space, String stored, EmbeddingGeneration configured) {
    if (stored.startsWith(PENDING_PREFIX))
      throw EmbeddingGenerationMismatchException.pending(space, stored, configured);
    if (!stored.equals(configured.value()))
      throw EmbeddingGenerationMismatchException.differs(space, stored, configured);
  }
}
