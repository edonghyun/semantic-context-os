package io.scos.engine;

import io.scos.spi.store.SpaceId;
import java.util.Objects;
import java.util.Optional;

/** read 요청: 문서의 유닛을 적재 순서로 offset부터 limit개. offset ≥ 0, limit은 1–{@value #MAX_LIMIT}. */
public record ReadRequest(Optional<SpaceId> space, String documentId, int offset, int limit) {
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  public ReadRequest {
    Objects.requireNonNull(space, "space");
  }

  public static ReadRequest of(String documentId) {
    return new ReadRequest(Optional.empty(), documentId, 0, DEFAULT_LIMIT);
  }
}
