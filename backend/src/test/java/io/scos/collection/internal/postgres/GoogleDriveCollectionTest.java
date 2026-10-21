package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.connector.googledrive.GoogleDriveAdapter;
import io.scos.connector.googledrive.internal.GoogleDriveCollectionContract;
import io.scos.engine.support.text.MutableClock;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

/**
 * {@link GoogleDriveCollectionContract}의 PostgreSQL 판(공용 `PostgresCollectionRuntime`). 시작 전에 DB를
 * 비운다(TESTING §6.1). 체크포인트는 jsonb로 저장되므로 키 순서가 아니라 값으로 비교한다.
 */
@DisplayName("Google Drive 수집 계약 — PostgreSQL 런타임 판(TESTING §3.6)")
@Tag("db")
class GoogleDriveCollectionTest extends GoogleDriveCollectionContract {
  @Override
  protected Assembled newRuntime(GoogleDriveAdapter adapter, MutableClock clock) {
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
