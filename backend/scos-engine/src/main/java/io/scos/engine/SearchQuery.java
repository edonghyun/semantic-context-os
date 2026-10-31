package io.scos.engine;

import io.scos.spi.store.SpaceId;
import java.util.Objects;
import java.util.Optional;

/**
 * search 질의. space가 비면 엔진의 기본 공간이다. limit은 1–{@value #MAX_LIMIT}. effort·profile의 기본은 MEDIUM·AUTO다.
 */
public record SearchQuery(
    Optional<SpaceId> space, String text, int limit, Effort effort, Profile profile) {
  public static final int DEFAULT_LIMIT = 5;
  public static final int MAX_LIMIT = 50;

  public SearchQuery {
    Objects.requireNonNull(space, "space");
    Objects.requireNonNull(effort, "effort");
    Objects.requireNonNull(profile, "profile");
  }

  public SearchQuery(Optional<SpaceId> space, String text, int limit) {
    this(space, text, limit, Effort.MEDIUM, Profile.AUTO);
  }

  public static SearchQuery of(String text) {
    return new SearchQuery(Optional.empty(), text, DEFAULT_LIMIT);
  }
}
