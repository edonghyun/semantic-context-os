package io.scos.engine;

import io.scos.spi.store.SpaceId;
import java.util.Objects;
import java.util.Optional;

/**
 * assemble 질의: 토큰 예산 안의 컨텍스트 묶음. tokenBudget은 {@value #MIN_TOKEN_BUDGET}–{@value
 * #MAX_TOKEN_BUDGET}. effort·profile의 기본은 MEDIUM·AUTO다.
 */
public record AssembleQuery(
    Optional<SpaceId> space, String text, int tokenBudget, Effort effort, Profile profile) {
  public static final int DEFAULT_TOKEN_BUDGET = 2000;
  public static final int MIN_TOKEN_BUDGET = 500;
  public static final int MAX_TOKEN_BUDGET = 8000;

  public AssembleQuery {
    Objects.requireNonNull(space, "space");
    Objects.requireNonNull(effort, "effort");
    Objects.requireNonNull(profile, "profile");
  }

  public AssembleQuery(Optional<SpaceId> space, String text, int tokenBudget) {
    this(space, text, tokenBudget, Effort.MEDIUM, Profile.AUTO);
  }

  public static AssembleQuery of(String text) {
    return new AssembleQuery(Optional.empty(), text, DEFAULT_TOKEN_BUDGET);
  }
}
