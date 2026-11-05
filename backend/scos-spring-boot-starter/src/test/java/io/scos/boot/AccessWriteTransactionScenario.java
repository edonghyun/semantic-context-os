package io.scos.boot;

import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 제품 PostgreSQL 조립의 세 쓰기가 호출자 롤백에 함께 참여하는지 관찰한다. */
@Tag("db")
final class AccessWriteTransactionScenario {
  static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private final PostgresCollectionConfiguration.Adapters adapters =
      PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
  private final RunId run = new RunId("access-rollback");

  AccessWriteTransactionScenario() {
    adapters.sources().save(SourceInstances.of(SOURCE, "google-drive", "{}", "env:FAKE", NOW));
    adapters.runs().start(run, SOURCE, NOW);
  }

  void rollbackAccessWrites() {
    var tx =
        new TransactionTemplate(new DataSourceTransactionManager(PostgresSupport.dataSource()));
    tx.executeWithoutResult(
        status -> {
          adapters.rawAcls().observe(SOURCE, "file:F1", RawAcl.notObserved(), NOW);
          adapters
              .accessObservations()
              .stage(
                  run,
                  new AclPage(
                      Map.of(), List.of(new GroupSnapshot("g1", Set.of("u1"), NOW)), List.of()));
          adapters.outbox().requestProjection(SOURCE, NOW);
          if (rawAcls().isEmpty() || observations() != 1 || !projectionPending())
            throw new IllegalStateException("given 준비 실패: 세 쓰기가 트랜잭션 안에 있어야 한다");
          status.setRollbackOnly();
        });
  }

  Map<String, RawAcl.Observed> rawAcls() {
    return adapters.rawAcls().get(SOURCE, List.of("file:F1"));
  }

  int observations() {
    return JdbcClient.create(PostgresSupport.dataSource())
        .sql("SELECT count(*)::int FROM access_observations")
        .query(Integer.class)
        .single();
  }

  boolean projectionPending() {
    return adapters.outbox().request(SOURCE).isPresent();
  }
}
