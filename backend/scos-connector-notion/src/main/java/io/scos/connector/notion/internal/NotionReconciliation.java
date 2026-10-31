package io.scos.connector.notion.internal;

import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 같은 run·lease에서 전체 cursor를 보존하고 내부 증분을 직렬 실행한다. */
public final class NotionReconciliation {
  public record Dependencies(
      NotionApiClient client, CollectionInput input, Clock clock, String token) {}

  // 응답 커밋 경계에서 확인하는 조각 예산이며 원격 호출의 강제 선점 시간이 아니다.
  private static final Duration FULL_SLICE_BUDGET = Duration.ofMinutes(1);
  private final Roots roots;
  private final NotionReconciliationState state;
  private final Dependencies deps;
  private final NotionCalls calls;
  private final NotionMutations mutations;

  private enum Phase {
    FULL,
    INCREMENTAL,
    VERIFYING
  }

  public NotionReconciliation(Roots roots, NotionReconciliationState state, Dependencies deps) {
    this.roots = roots;
    this.state = state;
    this.deps = deps;
    calls = new NotionCalls(deps.input(), deps.clock());
    mutations = new NotionMutations(deps.input().content(), deps.clock());
  }

  public void collect(PageSink sink) {
    new Session(sink).collect();
  }

  private final class Session {
    private final PageSink sink;
    private final NotionPageRefresh refresh;
    private final NotionDataSources.Dependencies dataSources;
    private Phase phase = Phase.FULL;
    private Instant sliceStartedAt;

    Session(PageSink sink) {
      this.sink = sink;
      state.checkBaseline(deps.input().state(), deps.input().cancel());
      sliceStartedAt = deps.clock().instant();
      refresh =
          new NotionPageRefresh(
              new NotionPageRefresh.Dependencies(
                  deps.client(),
                  calls,
                  mutations,
                  deps.input(),
                  state,
                  this::accept,
                  deps.token()));
      dataSources =
          new NotionDataSources.Dependencies(
              deps.client(),
              calls,
              mutations,
              deps.input(),
              state,
              this::accept,
              deps.token(),
              deps.clock(),
              refresh);
    }

    void collect() {
      if (state.incrementalDue(deps.clock().instant())) incremental();
      state.fullTraversal().ifPresent(this::collectFull);
    }

    private void collectFull(NotionRootTraversal traversal) {
      var sources = new NotionDataSources(dataSources);
      new NotionRootCollection(roots, traversal, deps.client(), calls, mutations, deps.token())
          .collectReconciled(this::accept, sources, scope());
      incremental();
      if (state.coverage() == CollectionCoverage.COMPLETE_SNAPSHOT) {
        phase = Phase.VERIFYING;
        new NotionAbsences(dataSources).verify();
        phase = Phase.FULL;
      }
    }

    private void incremental() {
      Phase previous = phase;
      phase = Phase.INCREMENTAL;
      try {
        new NotionIncrementalCollection(
                new NotionIncrementalCollection.Dependencies(
                    deps.client(),
                    calls,
                    mutations,
                    deps.input(),
                    state,
                    this::accept,
                    deps.token(),
                    deps.clock(),
                    refresh))
            .collect();
      } finally {
        phase = previous;
        sliceStartedAt = deps.clock().instant();
      }
    }

    private NotionScope scope() {
      return new NotionScope(state, deps.client(), calls, deps.token());
    }

    private void accept(CollectionPage page) {
      if (page.nextCheckpoint().schemaVersion() == 3) state.fullProgress(page.nextCheckpoint());
      sink.accept(
          new CollectionPage(
              page.mutations(),
              page.rejections(),
              state.checkpoint(),
              page.bodies(),
              page.absenceVerification(),
              page.reconciliations()));
      Instant now = deps.clock().instant();
      if (phase != Phase.INCREMENTAL
          && (state.fullCheckpoint().isPresent() || phase == Phase.VERIFYING)
          && Duration.between(sliceStartedAt, now).compareTo(FULL_SLICE_BUDGET) >= 0) {
        sliceStartedAt = now;
        if (state.incrementalDue(now)) incremental();
      }
    }
  }
}
