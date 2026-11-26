package io.scos.engine.internal.search.signal;

import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import java.time.Instant;

/** 후보 점수에 곱하는 계수. I/O 금지, 1.0이면 영향 없음. plan은 그 검색 호출의 계획이다. */
public interface Signal {
  double factor(Candidate c, RetrievalPlan plan, Instant now);
}
