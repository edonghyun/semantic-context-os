package io.scos.store.postgres;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.UserTokens;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 합성 관리 계약의 실제 저장 경계. 수집 조립 계약은 실제 SourceInstanceStore를 별도로 사용한다. */
public final class AdminStorageScenario {
  private final DataSource dataSource;
  private final AdminStore store;
  private final JdbcClient jdbc;

  public AdminStorageScenario(DataSource dataSource) {
    this.dataSource = dataSource;
    store = new PostgresAdminStore(dataSource);
    jdbc = JdbcClient.create(dataSource);
  }

  public AdminStore store() {
    return store;
  }

  public void registerSource(SourceAccount source) {
    jdbc.sql(
            """
        INSERT INTO source_instances (id,kind,display_name,config_schema_version,config,credential_ref,enabled,created_at,updated_at)
        VALUES (:id,:kind,'Contract source',1,'{}','env:CONTRACT',true,now(),now())
        ON CONFLICT (id) DO UPDATE SET kind=EXCLUDED.kind
        """)
        .param("id", source.id().value())
        .param("kind", source.kind())
        .update();
  }

  public UserTokens authentication() {
    return new PostgresUserTokens(dataSource, dataSource);
  }

  public void seed(AccessDirectory directory) {
    new PostgresAccessSeed(dataSource).seed(directory);
  }

  public void observeIdentity(SourceInstanceId source, SourceIdentityRecord identity) {
    new PostgresSourceAccessDirectory(jdbc)
        .save(source, new Snapshot(Map.of(identity.sourceUserId(), identity), Map.of()));
  }

  public IdentityStore identityReader() {
    return new PostgresIdentityStore(jdbc);
  }
}
