package io.scos.engine;

import io.scos.spi.store.SpaceId;
import java.util.Objects;
import java.util.Optional;

/** related 질의: 문서의 이웃 문서. limit은 1–{@value #MAX_LIMIT}. */
public record RelatedQuery(Optional<SpaceId> space, String documentId, int limit) {
  public static final int DEFAULT_LIMIT = 5;
  public static final int MAX_LIMIT = 50;

  public RelatedQuery {
    Objects.requireNonNull(space, "space");
  }

  public static RelatedQuery of(String documentId) {
    return new RelatedQuery(Optional.empty(), documentId, DEFAULT_LIMIT);
  }
}
