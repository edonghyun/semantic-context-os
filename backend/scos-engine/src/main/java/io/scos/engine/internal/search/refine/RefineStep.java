package io.scos.engine.internal.search.refine;

import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import java.util.List;

/** 후보 다듬기 한 단계. 값은 plan에서 읽고 순서는 QuestionSearch가 고정한다. */
public interface RefineStep {
  List<Candidate> apply(AccessScope access, SpaceId space, List<Candidate> in, RetrievalPlan plan);
}
