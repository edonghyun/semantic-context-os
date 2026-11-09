package io.scos.collection.internal;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.spi.connector.AccessCost;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** 비용 조회의 run 각본. 메모리는 실제 이력 정책을, PG는 운영 조립의 조회 포트를 실행한다. */
public final class AccessRunCostQueryScenario {
  private static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  private static final Instant STARTED_AT = Instant.parse("2026-10-14T00:00:00Z");
  private static final Instant RECORDED_AT = Instant.parse("2026-10-14T00:00:01Z");
  private final RunStore runs;
  private final SourceInstanceStore sources;
  private final Optional<SourceQueries> queries;
  private final List<RunId> ids = new ArrayList<>();

  private AccessRunCostQueryScenario(
      RunStore runs, SourceInstanceStore sources, Optional<SourceQueries> queries) {
    this.runs = runs;
    this.sources = sources;
    this.queries = queries;
    sources.save(SourceInstances.of(SOURCE, "google-drive", "{}", "env:FAKE", STARTED_AT));
  }

  public static AccessRunCostQueryScenario memory() {
    var runs = MemoryRunStore.create();
    return new AccessRunCostQueryScenario(
        runs, new MemorySourceInstanceStore(runs.identities()), Optional.empty());
  }

  public static AccessRunCostQueryScenario postgres(DataSource dataSource) {
    var adapters = PostgresCollectionConfiguration.adapters(dataSource);
    return new AccessRunCostQueryScenario(
        adapters.runs(), adapters.sources(), Optional.of(adapters.queries()));
  }

  public void givenResultRun(Optional<AccessCost> cost) {
    var id = new RunId("run-r1");
    start(id, SOURCE, STARTED_AT);
    cost.ifPresent(value -> runs.recordAccessCost(id, value, RECORDED_AT));
    runs.finish(
        id,
        RunOutcome.succeeded(0),
        CollectionCoverage.INCREMENTAL,
        RECORDED_AT,
        0,
        AccessResult.COMPLETE,
        Optional.of(RECORDED_AT));
  }

  public void givenNewRunningRun() {
    start(new RunId("run-r2"), SOURCE, STARTED_AT.plusSeconds(2));
  }

  public void givenNewUnmeasuredResultRun() {
    givenNewRunningRun();
    runs.finish(
        new RunId("run-r2"),
        RunOutcome.succeeded(0),
        CollectionCoverage.INCREMENTAL,
        STARTED_AT.plusSeconds(3),
        0,
        AccessResult.INCOMPLETE,
        Optional.of(STARTED_AT.plusSeconds(3)));
  }

  public void givenOtherSourceCost(AccessCost cost) {
    var source = new SourceInstanceId("drive-b");
    sources.save(SourceInstances.of(source, "google-drive", "{}", "env:FAKE", STARTED_AT));
    var id = new RunId("other-run");
    start(id, source, STARTED_AT.plusSeconds(4));
    runs.recordAccessCost(id, cost, STARTED_AT.plusSeconds(5));
  }

  private void start(RunId id, SourceInstanceId source, Instant at) {
    runs.start(id, source, at);
    ids.add(id);
  }

  public Optional<SourceQueries.RunCost> cost() {
    if (queries.isPresent()) return queries.orElseThrow().accessStatus(SOURCE).orElseThrow().cost();
    var newestFirst =
        ids.stream()
            .map(id -> runs.get(id).orElseThrow())
            .filter(run -> run.source().equals(SOURCE))
            .sorted(
                Comparator.comparing(CollectionRun::startedAt)
                    .thenComparing(run -> run.id().value())
                    .reversed())
            .toList();
    return AccessHistory.of(newestFirst).cost();
  }
}
