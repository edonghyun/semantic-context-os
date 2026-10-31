package io.scos.collection.internal.postgres;

import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.SnapshotShrinkGuardContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

/** {@link SnapshotShrinkGuardContract}의 PostgreSQL 판. 시작마다 전체 TRUNCATE. */
@DisplayName("스냅샷 축소 보호 계약 — PostgreSQL 포트 판(ADR-0028)")
@Tag("db")
class PostgresSnapshotShrinkGuardTest extends SnapshotShrinkGuardContract {
  @Override
  protected CommitContext.Ports newPorts() {
    PostgresSupport.truncateAll();
    return CommitContext.Ports.postgres(PostgresSupport.dataSource());
  }
}
