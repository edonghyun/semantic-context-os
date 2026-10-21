package io.scos.engine;

import io.scos.spi.store.SpaceId;
import java.util.Objects;
import java.util.Optional;

/**
 * assemble 질의: 토큰 예산 안의 컨텍스트 묶음. tokenBudget은 {@value #MIN_TOKEN_BUDGET}–{@value
 * #MAX_TOKEN_BUDGET}.
 */
public record AssembleQuery(Optional<SpaceId> space, String text, int tokenBudget) {
  public static final int DEFAULT_TOKEN_BUDGET = 2000;
  public static final int MIN_TOKEN_BUDGET = 500;
  public static final int MAX_TOKEN_BUDGET = 8000;

  public AssembleQuery {
    Objects.requireNonNull(space, "space");
  }

  public static AssembleQuery of(String text) {
    return new AssembleQuery(Optional.empty(), text, DEFAULT_TOKEN_BUDGET);
  }
}
