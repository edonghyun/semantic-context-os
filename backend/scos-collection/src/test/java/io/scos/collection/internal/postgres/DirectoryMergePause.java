package io.scos.collection.internal.postgres;

import io.scos.support.Eventually;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 병합의 신원 INSERT 직전에 멈춘다. 소스 행 잠금은 제품 코드만 획득한다. */
public final class DirectoryMergePause implements AutoCloseable {
  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final long GATE = 20452002;
  private final Connection connection;
  private final JdbcClient gate;
  private final JdbcClient database;
  private boolean locked = true;

  public DirectoryMergePause(DataSource dataSource) {
    try {
      connection = dataSource.getConnection();
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
    var session = new SingleConnectionDataSource(connection, true);
    gate = JdbcClient.create(session);
    database = JdbcClient.create(dataSource);
    try {
      new TransactionTemplate(new DataSourceTransactionManager(session))
          .executeWithoutResult(status -> installPause());
    } catch (RuntimeException | Error failure) {
      try {
        closeConnection();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private void installPause() {
    gate.sql("SELECT pg_advisory_lock(:gate)").param("gate", GATE).query().singleRow();
    gate.sql(
            """
        CREATE FUNCTION pause_admin_directory_merge() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN PERFORM pg_advisory_xact_lock(20452002); RETURN NEW; END $$
        """)
        .update();
    gate.sql(
            """
        CREATE TRIGGER pause_admin_directory_merge BEFORE INSERT ON source_identities
        FOR EACH ROW EXECUTE FUNCTION pause_admin_directory_merge()
        """)
        .update();
  }

  public void awaitHeld() {
    Eventually.await(
        "directory merge is waiting at the identity insert",
        () ->
            database
                    .sql(
                        """
            SELECT count(*) FROM pg_locks
            WHERE locktype = 'advisory' AND objid = :gate AND NOT granted
            """)
                    .param("gate", GATE)
                    .query(Integer.class)
                    .single()
                > 0,
        WAIT);
  }

  public void release() {
    if (!locked) return;
    gate.sql("SELECT pg_advisory_unlock(:gate)").param("gate", GATE).query(Boolean.class).single();
    locked = false;
  }

  @Override
  public void close() {
    try {
      database
          .sql("DROP TRIGGER IF EXISTS pause_admin_directory_merge ON source_identities")
          .update();
      database.sql("DROP FUNCTION IF EXISTS pause_admin_directory_merge()").update();
    } finally {
      closeConnection();
    }
  }

  private void closeConnection() {
    try {
      connection.close();
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
