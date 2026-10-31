package io.scos.engine.internal.search;

import io.scos.engine.support.PureFunctions;
import io.scos.spi.store.ContextUnit;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** 명시적 Current의 반환 순서. 관련도 점수와 유닛은 보존한다(CONTRACTS B12). */
@PureFunctions
public final class CurrentOrder {
  /** 관련 상위 안에서만 최신순으로 고른다. MCP limit 상한 10에서 후보는 최대 20개다. */
  private static final int RELEVANCE_WINDOW_MULTIPLIER = 2;

  private CurrentOrder() {}

  public static List<Candidate> search(List<Candidate> candidates, int limit) {
    return candidates.stream()
        .limit((long) RELEVANCE_WINDOW_MULTIPLIER * limit)
        .sorted(Comparator.comparing(c -> capturedAt(c.unit()), Comparator.reverseOrder()))
        .limit(limit)
        .toList();
  }

  /** 예산 선택과 U자 배치를 마친 순서가 동률의 원순서다. */
  public static List<ContextAssembler.Item> assemble(List<ContextAssembler.Item> items) {
    return items.stream()
        .sorted(Comparator.comparing(i -> capturedAt(i.unit()), Comparator.reverseOrder()))
        .toList();
  }

  private static Instant capturedAt(ContextUnit unit) {
    return new CapturedAt(unit.provenance().capturedAt()).instant().orElse(Instant.MIN);
  }
}
