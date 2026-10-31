package io.scos.store.sqlite;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.IdentityStoreContract;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("신원 저장소 계약 — SQLite 포트")
class SqliteIdentityStoreTest extends IdentityStoreContract {
  private static final String OBSERVED = "2026-10-26T00:00:00.000000000Z";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path dir;
  private JdbcClient jdbc;

  @Override
  protected IdentityStore newStore() {
    Path file = dir.resolve("index.sqlite");
    var index = SqliteIndex.open(file);
    var dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
    jdbc = JdbcClient.create(dataSource);
    return index.identities();
  }

  /** 테스트 전용 SQL 삽입 — 운영 쓰기 포트가 아니다. */
  @Override
  protected void seed(AccessDirectory directory) {
    directory
        .users()
        .forEach(
            u ->
                jdbc.sql(
                        "INSERT INTO users (id, email, active, role, created_at)"
                            + " VALUES (:id, :email, :active, 'member', :at)")
                    .param("id", u.id())
                    .param("email", u.email())
                    .param("active", u.active() ? 1 : 0)
                    .param("at", OBSERVED)
                    .update());
    directory
        .identities()
        .forEach(
            i ->
                jdbc.sql(
                        "INSERT INTO source_identities (source_instance_id, source_user_id,"
                            + " email_verified, guest, deleted, observed_at)"
                            + " VALUES (:instance, :id, 0, :guest, :deleted, :at)")
                    .param("instance", i.instance().value())
                    .param("id", i.id())
                    .param("guest", i.guest() ? 1 : 0)
                    .param("deleted", i.deleted() ? 1 : 0)
                    .param("at", OBSERVED)
                    .update());
    directory
        .links()
        .forEach(
            l ->
                jdbc.sql(
                        "INSERT INTO user_source_identities (source_instance_id, source_user_id,"
                            + " user_id, method, linked_at, linked_by)"
                            + " VALUES (:instance, :id, :user, :method, :at, 'contract-admin')")
                    .param("instance", l.instance().value())
                    .param("id", l.id())
                    .param("user", l.userId())
                    .param("method", l.method())
                    .param("at", OBSERVED)
                    .update());
    directory.groups().forEach(this::insertGroup);
    directory
        .scopes()
        .forEach(
            s ->
                jdbc.sql(
                        "INSERT INTO org_wide_scopes (source_instance_id, designated_by,"
                            + " designated_at) VALUES (:instance, 'contract-admin', :at)")
                    .param("instance", s.value())
                    .param("at", OBSERVED)
                    .update());
  }

  private void insertGroup(AccessDirectory.Group group) {
    List<String> members = group.members().stream().sorted().toList();
    var statement =
        jdbc.sql(
                "INSERT INTO source_groups (source_instance_id, group_id, members, fingerprint,"
                    + " observed_at) VALUES (:instance, :id, :members, 'contract', :at)")
            .param("instance", group.instance().value())
            .param("id", group.id())
            .param("members", JSON.writeValueAsString(members))
            .param("at", OBSERVED);
    statement.update();
  }
}
