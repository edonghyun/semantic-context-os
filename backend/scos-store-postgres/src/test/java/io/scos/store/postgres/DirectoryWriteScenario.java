package io.scos.store.postgres;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL 행 트리거로 재관측의 실제 쓰기 수를 센다. */
@Tag("db")
final class DirectoryWriteScenario implements AutoCloseable {
  private static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());
  private final SourceAccessDirectory directory = new PostgresSourceAccessDirectory(jdbc);

  DirectoryWriteScenario() {
    PostgresSupport.truncateAll();
    jdbc.sql("CREATE TABLE directory_write_counts (kind text)").update();
    jdbc.sql(
            """
        CREATE FUNCTION count_directory_writes() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN INSERT INTO directory_write_counts VALUES (TG_TABLE_NAME); RETURN NULL; END $$
        """)
        .update();
    jdbc.sql(
            "CREATE TRIGGER count_directory_writes AFTER INSERT OR UPDATE ON source_groups FOR EACH ROW EXECUTE FUNCTION count_directory_writes()")
        .update();
    jdbc.sql(
            "CREATE TRIGGER count_directory_writes AFTER INSERT OR UPDATE ON source_identities FOR EACH ROW EXECUTE FUNCTION count_directory_writes()")
        .update();
    saveAt(NOW);
    jdbc.sql("TRUNCATE directory_write_counts").update();
  }

  void saveAt(Instant at) {
    var identity =
        new SourceIdentityRecord(
            "reader", Optional.of("reader@example.com"), true, false, false, at);
    var group = new GroupSnapshot("team", Set.of("reader"), at);
    directory.save(
        SOURCE,
        new SourceAccessDirectory.Snapshot(Map.of("reader", identity), Map.of("team", group)));
  }

  void sameObservation() {
    saveAt(NOW);
  }

  void newerObservation() {
    saveAt(NOW.plusSeconds(1));
  }

  int writes() {
    return jdbc.sql("SELECT count(*) FROM directory_write_counts").query(Integer.class).single();
  }

  @Override
  public void close() {
    jdbc.sql("DROP TRIGGER count_directory_writes ON source_groups").update();
    jdbc.sql("DROP TRIGGER count_directory_writes ON source_identities").update();
    jdbc.sql("DROP FUNCTION count_directory_writes()").update();
    jdbc.sql("DROP TABLE directory_write_counts").update();
  }
}
