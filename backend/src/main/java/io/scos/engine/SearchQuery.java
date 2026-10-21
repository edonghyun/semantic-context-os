package io.scos.engine;

import io.scos.spi.store.SpaceId;
import java.util.Objects;
import java.util.Optional;

/** search 질의. space가 비면 엔진의 기본 공간이다. limit은 1–{@value #MAX_LIMIT}. */
public record SearchQuery(Optional<SpaceId> space, String text, int limit) {
  public static final int DEFAULT_LIMIT = 5;
  public static final int MAX_LIMIT = 50;

  public SearchQuery {
    Objects.requireNonNull(space, "space");
  }

  public static SearchQuery of(String text) {
    return new SearchQuery(Optional.empty(), text, DEFAULT_LIMIT);
  }
}
