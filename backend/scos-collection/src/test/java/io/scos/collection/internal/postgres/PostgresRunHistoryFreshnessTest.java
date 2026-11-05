package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunOutcome;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.RunStore;
import io.scos.spi.access.SourceFreshness;
import io.scos.spi.access.SourceFreshnessContract;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@DisplayName("Postgres run 기반 최신성은 원천 최신성 계약을 지킨다(B9)")
@Tag("db")
class PostgresRunHistoryFreshnessTest extends SourceFreshnessContract {
  private RunStore runs;
  private int sequence;

  @Override
  protected SourceFreshness newFreshness() {
    PostgresSupport.truncateAll();
    runs = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).runs();
    sequence = 0;
    return new PostgresCollectionConfiguration().newSourceFreshness(PostgresSupport.dataSource());
  }

  @Override
  protected void recordSuccess(SourceInstanceId instance, Instant finishedAt) {
    record(instance, finishedAt, RunStatus.SUCCEEDED);
  }

  @Override
  protected void recordNonSuccess(SourceInstanceId instance, Instant finishedAt) {
    record(instance, finishedAt, RunStatus.FAILED);
  }

  private void record(SourceInstanceId instance, Instant at, RunStatus status) {
    RunId id = new RunId("freshness-" + sequence++);
    runs.start(id, instance, at);
    runs.finish(
        id,
        new RunOutcome(status, 0, Optional.empty(), Optional.empty()),
        CollectionCoverage.INCREMENTAL,
        at,
        0,
        AccessResult.SKIPPED,
        Optional.empty());
  }
}
