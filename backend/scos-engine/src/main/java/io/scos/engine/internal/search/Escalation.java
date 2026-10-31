package io.scos.engine.internal.search;

import io.scos.engine.Effort;
import io.scos.engine.internal.search.plan.PlanResolver;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import java.util.Optional;

/** 자동 상향 규칙: LOW에서 결과가 모자라면 MEDIUM으로 한 번. MEDIUM→HIGH는 비용 때문에 자동으로 하지 않는다. */
public final class Escalation {
  private final PlanResolver resolver;

  public Escalation(PlanResolver resolver) {
    this.resolver = resolver;
  }

  Optional<Effort> next(Effort used, int hits, int limit) {
    return used == Effort.LOW && hits < limit ? Optional.of(Effort.MEDIUM) : Optional.empty();
  }

  /** 상향할 때만 다시 풀 계획(날짜 가산 등 프로필 결정은 보존). */
  Optional<RetrievalPlan> replan(RetrievalPlan used, int hits, int limit) {
    return next(used.effort(), hits, limit).map(to -> resolver.escalate(used, to));
  }
}
