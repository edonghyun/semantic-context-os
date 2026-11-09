package io.scos.collection.internal.postgres;

import com.zaxxer.hikari.HikariDataSource;
import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.AccessMerge;
import io.scos.collection.internal.AccessObservationStore;
import io.scos.collection.internal.ClaimLostException;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SourceInstances;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.IdentityCoverage;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceInstanceId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** 실제 풀의 unlock 실패를 주입하고 독립 연결에서 잠금 반환을 관찰한다. */
@Tag("db")
final class AccessMergeLockScenario implements AutoCloseable {
  private static final Instant AT = Instant.parse("2026-11-03T00:00:00Z");
  private static final SourceInstanceId SOURCE = new SourceInstanceId("merge-source");
  private static final RunId RUN = new RunId("merge-run");
  final ClaimLostException lost = new ClaimLostException("injected lost claim");
  private final HikariDataSource pool = new HikariDataSource();
  private final AccessObservationStore store;

  AccessMergeLockScenario() {
    PostgresSupport.truncateAll();
    pool.setJdbcUrl(PostgresSupport.jdbcUrl());
    pool.setUsername(PostgresSupport.username());
    pool.setPassword(PostgresSupport.password());
    pool.setMaximumPoolSize(1);
    pool.setMinimumIdle(0);
    var adapters = PostgresCollectionConfiguration.adapters(pool);
    adapters.sources().save(SourceInstances.noop(SOURCE, AT));
    adapters.runs().start(RUN, SOURCE, AT);
    store =
        PostgresCollectionConfiguration.adapters(new UnlockFailureDataSource())
            .accessObservations();
  }

  void mergeWithLostClaim() {
    store.merge(
        RUN,
        SOURCE,
        AccessMerge.COMPLETE,
        IdentityCoverage.PARTIAL,
        AT,
        () -> {
          throw lost;
        });
  }

  void givenLostClaim() {
    try {
      mergeWithLostClaim();
    } catch (RuntimeException expected) {
      // 잠금 반환 계약의 전제다. 원래 예외 보존은 별도 테스트가 확인한다.
    }
  }

  boolean runLockIsAvailable() {
    return JdbcClient.create(PostgresSupport.dataSource())
        .sql("SELECT pg_try_advisory_xact_lock(hashtextextended('scos.access:merge-run', 0))")
        .query(Boolean.class)
        .single();
  }

  void rejectDirectoryWrites() {
    var preparing = PostgresCollectionConfiguration.adapters(pool).accessObservations();
    preparing.stage(
        RUN, new AclPage(Map.of(), List.of(new GroupSnapshot("C1", Set.of(), AT)), List.of()));
    preparing.merge(RUN, SOURCE, AccessMerge.COMPLETE, IdentityCoverage.PARTIAL, AT, () -> {});
    preparing.discard(RUN);
    JdbcClient.create(pool)
        .sql(
            """
        CREATE FUNCTION reject_empty_directory_write() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'empty incomplete merge wrote directory'; END $$
        """)
        .update();
    JdbcClient.create(pool)
        .sql(
            """
        CREATE TRIGGER reject_empty_directory_write BEFORE UPDATE ON source_groups
        FOR EACH ROW EXECUTE FUNCTION reject_empty_directory_write()
        """)
        .update();
  }

  AccessMerge mergeEmptyIncomplete() {
    return store.merge(RUN, SOURCE, AccessMerge.INCOMPLETE, IdentityCoverage.PARTIAL, AT, () -> {});
  }

  private final class UnlockFailureDataSource extends AbstractDataSource {
    @Override
    public Connection getConnection() throws SQLException {
      Connection delegate = pool.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")
                    && args != null
                    && args[0] instanceof String sql
                    && sql.contains("pg_advisory_unlock("))
                  throw new SQLException("injected unlock failure");
                try {
                  return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }
  }

  @Override
  public void close() {
    JdbcClient.create(pool)
        .sql("DROP TRIGGER IF EXISTS reject_empty_directory_write ON source_groups")
        .update();
    JdbcClient.create(pool).sql("DROP FUNCTION IF EXISTS reject_empty_directory_write()").update();
    pool.close();
  }
}
