package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.connector.tiro.TiroAdapter;
import io.scos.connector.tiro.internal.TiroCollectionContract;
import io.scos.engine.support.text.MutableClock;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

/**
 * {@link TiroCollectionContract}의 PostgreSQL 판(공용 `PostgresCollectionRuntime`). 시작 전에 DB를
 * 비운다(TESTING §6.1).
 */
@DisplayName("Tiro 수집 계약 — PostgreSQL 런타임 판(TESTING §3.6)")
@Tag("db")
class TiroCollectionTest extends TiroCollectionContract {
  @Override
  protected Assembled newRuntime(TiroAdapter adapter, MutableClock clock) {
    PostgresSupport.truncateAll();
    DataSource ds = PostgresSupport.dataSource();
    var adapters = PostgresCollectionConfiguration.adapters(ds);
    return new Assembled(
        PostgresCollectionRuntime.over(ds, clock).adapter(adapter).build(),
        adapters.sources(),
        adapters.rawItems(),
        adapters.checkpoints());
  }
}
