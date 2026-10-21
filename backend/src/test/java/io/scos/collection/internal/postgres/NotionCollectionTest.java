package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.connector.notion.NotionAdapter;
import io.scos.connector.notion.internal.NotionCollectionContract;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.text.MutableClock;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

/**
 * {@link NotionCollectionContract}의 PostgreSQL 판(TESTING §3.6 6층, 공용 `PostgresCollectionRuntime`).
 * 시작 전에 DB를 비운다(TESTING §6.1).
 */
@DisplayName("Notion 수집 계약 — PostgreSQL 런타임 판(TESTING §3.6)")
@Tag("db")
class NotionCollectionTest extends NotionCollectionContract {
  @Override
  protected Assembled newRuntime(
      NotionAdapter adapter, MutableClock clock, ProviderCallExecutor calls) {
    PostgresSupport.truncateAll();
    DataSource ds = PostgresSupport.dataSource();
    var adapters = PostgresCollectionConfiguration.adapters(ds);
    return new Assembled(
        PostgresCollectionRuntime.over(ds, clock).adapter(adapter).calls(calls).build(),
        adapters.sources(),
        adapters.rawItems(),
        adapters.runs(),
        adapters.checkpoints(),
        adapters.guards());
  }
}
