package io.scos.store.postgres;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.Group;
import io.scos.spi.access.AccessDirectory.Identity;
import io.scos.spi.access.AccessDirectory.Link;
import io.scos.spi.access.AccessDirectory.User;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 접근 계약과 HTTP 종단 테스트가 쓰는 SQL 시드. 운영 쓰기 포트가 아니다. */
public final class PostgresAccessSeed {
  private static final Instant OBSERVED = Instant.parse("2026-10-26T00:00:00Z");
  private final JdbcClient jdbc;

  public PostgresAccessSeed(DataSource dataSource) {
    jdbc = JdbcClient.create(dataSource);
  }

  public void seed(AccessDirectory directory) {
    directory.users().forEach(this::user);
    directory.identities().forEach(this::identity);
    directory.links().forEach(this::link);
    directory.groups().forEach(this::group);
    directory
        .scopes()
        .forEach(
            instance ->
                jdbc.sql(
                        """
        INSERT INTO org_wide_scopes (source_instance_id, designated_by, designated_at)
        VALUES (:instance, 'contract-admin', :observed)
        """)
                    .param("instance", instance.value())
                    .param("observed", Timestamp.from(OBSERVED))
                    .update());
  }

  private void user(User user) {
    jdbc.sql(
            """
        INSERT INTO users (id, email, active, role, created_at)
        VALUES (:id, :email, :active, 'member', :observed)
        """)
        .param("id", user.id())
        .param("email", user.email())
        .param("active", user.active())
        .param("observed", Timestamp.from(OBSERVED))
        .update();
  }

  private void identity(Identity identity) {
    jdbc.sql(
            """
        INSERT INTO source_identities
          (source_instance_id, source_user_id, email_verified, guest, deleted, observed_at)
        VALUES (:instance, :id, false, :guest, :deleted, :observed)
        """)
        .param("instance", identity.instance().value())
        .param("id", identity.id())
        .param("guest", identity.guest())
        .param("deleted", identity.deleted())
        .param("observed", Timestamp.from(OBSERVED))
        .update();
  }

  private void link(Link link) {
    jdbc.sql(
            """
        INSERT INTO user_source_identities
          (source_instance_id, source_user_id, user_id, method, linked_at, linked_by)
        VALUES (:instance, :id, :user, :method, :observed, 'contract-admin')
        """)
        .param("instance", link.instance().value())
        .param("id", link.id())
        .param("user", link.userId())
        .param("method", link.method())
        .param("observed", Timestamp.from(OBSERVED))
        .update();
  }

  private void group(Group group) {
    jdbc.sql(
            """
        INSERT INTO source_groups (source_instance_id, group_id, members, fingerprint, observed_at)
        VALUES (:instance, :id, :members, 'contract', :observed)
        """)
        .param("instance", group.instance().value())
        .param("id", group.id())
        .param("members", group.members().toArray(String[]::new))
        .param("observed", Timestamp.from(OBSERVED))
        .update();
  }
}
