package io.scos.store.postgres;

import io.scos.collection.internal.postgres.MigrationRehearsal;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.store.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 합성 V33 관리 행을 넣고 V34 이후의 메타데이터와 인증을 관찰한다. */
final class AdminMigrationScenario {
  private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");
  private static final TokenHash ACTIVE = TokenHash.of("synthetic-active");
  private static final TokenHash REVOKED = TokenHash.of("synthetic-revoked");
  private final MigrationRehearsal rehearsal;
  private final JdbcClient jdbc;
  private final UserTokens tokens;

  AdminMigrationScenario(String database) {
    rehearsal = MigrationRehearsal.freshDatabase(database).migrateTo("33");
    jdbc = rehearsal.jdbc();
    tokens = new PostgresUserTokens(rehearsal.dataSource(), rehearsal.dataSource());
    seedAccounts();
    seedAccess();
    tokens.store(ACTIVE, "alice", "work", AT);
    tokens.store(REVOKED, "bob", "retired", AT);
    tokens.revoke(REVOKED, AT);
  }

  void migrate() {
    rehearsal.migrateToLatest();
  }

  List<UUID> tokenIds() {
    return jdbc.sql("SELECT token_id FROM user_tokens ORDER BY user_id").query(UUID.class).list();
  }

  List<Optional<Principal>> authentication() {
    return List.of(tokens.authenticate(ACTIVE), tokens.authenticate(REVOKED));
  }

  List<String> retainedData() {
    return jdbc.sql(
            """
        SELECT 'user:' || id || ':' || email || ':' || active || ':' || role || ':' ||
               extract(epoch FROM created_at)::bigint AS value FROM users
        UNION ALL SELECT 'link:' || source_instance_id || ':' || source_user_id || ':' ||
               user_id || ':' || method || ':' || linked_by || ':' ||
               extract(epoch FROM linked_at)::bigint FROM user_source_identities
        UNION ALL SELECT 'scope:' || source_instance_id || ':' || designated_by || ':' ||
               extract(epoch FROM designated_at)::bigint FROM org_wide_scopes
        UNION ALL SELECT 'audit:' || id || ':' || actor || ':' || action || ':' || target_type ||
               ':' || target_id || ':' || detail::text || ':' ||
               extract(epoch FROM at)::bigint FROM audit_events
        ORDER BY value
        """)
        .query(String.class)
        .list();
  }

  void storeNewToken() {
    tokens.store(TokenHash.of("synthetic-new"), "alice", "new", AT);
  }

  void storeDuplicateMetadataId() {
    jdbc.sql(
            """
        INSERT INTO user_tokens (token_hash, user_id, label, created_at, token_id)
        SELECT :hash, 'alice', 'duplicate', created_at, token_id
        FROM user_tokens WHERE user_id = 'alice'
        """)
        .param("hash", TokenHash.of("synthetic-duplicate").value())
        .update();
  }

  void storeNullMetadataId() {
    jdbc.sql(
            """
        INSERT INTO user_tokens (token_hash, user_id, label, created_at, token_id)
        VALUES (:hash, 'alice', 'null-id', '2026-11-01T00:00:00Z', NULL)
        """)
        .param("hash", TokenHash.of("synthetic-null-id").value())
        .update();
  }

  String auditKeysetIndex() {
    return jdbc.sql(
            """
        SELECT indexdef FROM pg_indexes
        WHERE schemaname = 'public' AND tablename = 'audit_events'
          AND indexname = 'audit_events_keyset'
        """)
        .query(String.class)
        .single();
  }

  private void seedAccounts() {
    jdbc.sql(
            """
        INSERT INTO users (id, email, active, role, created_at) VALUES
          ('alice', 'alice@example.com', true, 'admin', '2026-11-01T00:00:00Z'),
          ('bob', 'bob@example.com', true, 'member', '2026-11-01T00:00:00Z')
        """)
        .update();
  }

  private void seedAccess() {
    jdbc.sql(
            """
        INSERT INTO user_source_identities VALUES
          ('slack-a', 'U01', 'alice', 'admin', '2026-11-01T00:00:00Z', 'alice')
        """)
        .update();
    jdbc.sql(
            """
        INSERT INTO org_wide_scopes VALUES ('notion-a', 'alice', '2026-11-01T00:00:00Z')
        """)
        .update();
    jdbc.sql(
            """
        INSERT INTO audit_events (at, actor, action, target_type, target_id, detail) VALUES
          ('2026-11-01T00:00:00Z', 'alice', 'user.create', 'user', 'bob', '{"role":"member"}')
        """)
        .update();
  }
}
