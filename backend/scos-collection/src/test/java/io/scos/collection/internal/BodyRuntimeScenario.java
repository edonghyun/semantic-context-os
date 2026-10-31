package io.scos.collection.internal;

import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionState;
import io.scos.spi.connector.SourceAdapter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 어댑터는 관찰용 소비자이며 읽기 요약의 저장·조회는 제품 유스케이스를 통과한다. */
public final class BodyRuntimeScenario {
  private final CollectionRuntime runtime;
  private Map<String, CollectionState.BodySummary> received = Map.of();

  public BodyRuntimeScenario(
      CommitContext.Ports ports,
      BodyCompletionStore bodies,
      Function<SourceAdapter, CollectionRuntime> runtime) {
    ports.sources().save(SourceInstances.noop(BodyCommitScenario.SOURCE, BodyCommitScenario.NOW));
    ports
        .rawItems()
        .apply(
            BodyCommitScenario.SOURCE,
            RawItems.item(BodyCommitScenario.PAGE)
                .version("v1")
                .order(BodyCommitScenario.ORDER)
                .upsert());
    bodies.save(
        BodyCommitScenario.SOURCE,
        new BodyCompletionStore.Body(
            BodyCommitScenario.FINGERPRINT,
            BodyCommitScenario.PAGE,
            "v1",
            BodyCommitScenario.ORDER,
            BodyCommitScenario.NOW,
            Set.of()));
    this.runtime =
        runtime.apply(
            SourceInstances.adapter(
                input ->
                    received =
                        input
                            .state()
                            .bodies(
                                BodyCommitScenario.FINGERPRINT,
                                List.of(BodyCommitScenario.PAGE),
                                input.cancel())));
  }

  public Map<String, CollectionState.BodySummary> collect() {
    runtime.collect(
        BodyCommitScenario.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    return received;
  }
}
