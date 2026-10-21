package io.scos.store.postgres;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.SpaceMetaContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@DisplayName("공간 메타 계약 — PostgreSQL 판(ADR-0018 §6)")
@Tag("db")
class PostgresSpaceMetaTest extends SpaceMetaContract {
  @Override
  protected SpaceMeta newMeta() {
    PostgresSupport.truncateAll();
    return PostgresTestAdapters.spaceMeta(PostgresSupport.dataSource());
  }
}
