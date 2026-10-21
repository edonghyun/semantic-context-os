package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.SnapshotShrinkGuardContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link SnapshotShrinkGuardContract}의 PostgreSQL 판. 시작마다 전체 TRUNCATE. */
@DisplayName("스냅샷 축소 보호 계약 — PostgreSQL 포트 판(ADR-0028)")
@Tag("db")
class PostgresSnapshotShrinkGuardTest extends SnapshotShrinkGuardContract {
  @Override
  protected Ports newPorts() {
    PostgresSupport.truncateAll();
    var ds = PostgresSupport.dataSource();
    var adapters = PostgresCollectionConfiguration.adapters(ds);
    return new Ports(
        new TransactionTemplate(new DataSourceTransactionManager(ds)),
        adapters.claims(),
        adapters.runs(),
        adapters.rawItems(),
        adapters.outbox(),
        adapters.changes(),
        adapters.sources(),
        adapters.guards());
  }
}
