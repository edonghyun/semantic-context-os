package io.scos.store.postgres;

import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.ObservedIdentity;
import io.scos.spi.access.AdminRecords.Page;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.StoredToken;
import io.scos.spi.access.AdminRecords.TokenMetadata;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 같은 쓰기 DataSource의 관리 변경·인증 상태·감사를 하나의 트랜잭션으로 저장한다. */
public final class PostgresAdminStore implements AdminStore {
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final UserTokens tokens;
  private final JsonMapper json = JsonMapper.builder().build();

  public PostgresAdminStore(DataSource dataSource) {
    jdbc = JdbcClient.create(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tokens = new PostgresUserTokens(dataSource, dataSource);
  }

  @Override
  public <T> T transact(Function<Session, T> work) {
    return transactions.execute(
        status -> {
          var session = new Transaction();
          try {
            return work.apply(session);
          } finally {
            session.open = false;
          }
        });
  }

  private final class Transaction implements Session, UserTokens {
    private final Thread owner = Thread.currentThread();
    private boolean open = true;

    private void requireOpen() {
      if (!open || !Thread.currentThread().equals(owner))
        throw new IllegalStateException(
            "Administration session is closed or belongs to another thread");
    }

    @Override
    public void lockAdministration() {
      requireOpen();
      jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('scos.admin',0))")
          .query((rs, row) -> true)
          .single();
    }

    @Override
    public int administratorCount() {
      requireOpen();
      return jdbc.sql("SELECT count(*) FROM users WHERE role='admin'")
          .query(Integer.class)
          .single();
    }

    @Override
    public int tokenHoldingAdministratorCount() {
      requireOpen();
      return jdbc.sql(
              """
          SELECT count(*) FROM users u WHERE u.active AND u.role='admin'
          AND EXISTS (SELECT 1 FROM user_tokens t WHERE t.user_id=u.id AND t.revoked_at IS NULL)
          """)
          .query(Integer.class)
          .single();
    }

    @Override
    public Optional<UserAccount> user(String id) {
      requireOpen();
      return jdbc.sql("SELECT * FROM users WHERE id=:id")
          .param("id", id)
          .query(PostgresAdminStore::userRow)
          .optional();
    }

    @Override
    public Optional<UserAccount> userByEmail(String email) {
      requireOpen();
      return jdbc.sql("SELECT * FROM users WHERE email=:email")
          .param("email", email)
          .query(PostgresAdminStore::userRow)
          .optional();
    }

    @Override
    public Page<UserAccount> users(PageRequest page) {
      requireOpen();
      var after = page.after().map(value -> decode("users", value));
      var rows =
          jdbc.sql(
                  "SELECT * FROM users WHERE (:after IS NULL OR id COLLATE \"C\" > :after) ORDER BY id COLLATE \"C\" LIMIT :limit")
              .param("after", after.orElse(null), Types.VARCHAR)
              .param("limit", page.limit() + 1)
              .query(PostgresAdminStore::userRow)
              .list();
      return page(rows, page.limit(), "users", UserAccount::id);
    }

    @Override
    public void insertUser(UserAccount user) {
      requireOpen();
      jdbc.sql(
              "INSERT INTO users(id,email,active,role,created_at) VALUES(:id,:email,:active,:role,:at)")
          .param("id", user.id())
          .param("email", user.email())
          .param("active", user.active())
          .param("role", wire(user.role()))
          .param("at", Timestamp.from(user.createdAt()))
          .update();
    }

    @Override
    public boolean setActive(String id, boolean active) {
      requireOpen();
      return jdbc.sql(
                  "UPDATE users SET active=:active WHERE id=:id AND active IS DISTINCT FROM :active")
              .param("id", id)
              .param("active", active)
              .update()
          > 0;
    }

    @Override
    public boolean setRole(String id, Role role) {
      requireOpen();
      return jdbc.sql("UPDATE users SET role=:role WHERE id=:id AND role IS DISTINCT FROM :role")
              .param("id", id)
              .param("role", wire(role))
              .update()
          > 0;
    }

    @Override
    public boolean lockSource(SourceInstanceId source) {
      requireOpen();
      return jdbc.sql("SELECT id FROM source_instances WHERE id=:source FOR UPDATE")
          .param("source", source.value())
          .query(String.class)
          .optional()
          .isPresent();
    }

    @Override
    public Optional<SourceAccount> source(SourceInstanceId source) {
      requireOpen();
      return jdbc.sql("SELECT id,kind FROM source_instances WHERE id=:source")
          .param("source", source.value())
          .query(
              (rs, row) ->
                  new SourceAccount(new SourceInstanceId(rs.getString("id")), rs.getString("kind")))
          .optional();
    }

    @Override
    public List<SourceAccount> sources() {
      requireOpen();
      return jdbc.sql("SELECT id,kind FROM source_instances ORDER BY id COLLATE \"C\"")
          .query(
              (rs, row) ->
                  new SourceAccount(new SourceInstanceId(rs.getString("id")), rs.getString("kind")))
          .list();
    }

    @Override
    public List<ObservedIdentity> observedIdentities(SourceInstanceId source) {
      requireOpen();
      return jdbc.sql(
              "SELECT * FROM source_identities WHERE source_instance_id=:source ORDER BY source_user_id COLLATE \"C\"")
          .param("source", source.value())
          .query(
              (rs, row) ->
                  new ObservedIdentity(
                      source,
                      new SourceIdentityRecord(
                          rs.getString("source_user_id"),
                          Optional.ofNullable(rs.getString("email")),
                          rs.getBoolean("email_verified"),
                          rs.getBoolean("guest"),
                          rs.getBoolean("deleted"),
                          rs.getTimestamp("observed_at").toInstant())))
          .list();
    }

    @Override
    public Optional<IdentityLink> link(SourceInstanceId source, String id) {
      requireOpen();
      return jdbc.sql(
              "SELECT * FROM user_source_identities WHERE source_instance_id=:source AND source_user_id=:id")
          .param("source", source.value())
          .param("id", id)
          .query(PostgresAdminStore::linkRow)
          .optional();
    }

    @Override
    public boolean putLink(IdentityLink link) {
      requireOpen();
      var changed =
          jdbc.sql(
                  """
          INSERT INTO user_source_identities(source_instance_id,source_user_id,user_id,method,linked_at,linked_by)
          VALUES(:source,:id,:user,:method,:at,:actor)
          ON CONFLICT(source_instance_id,source_user_id) DO UPDATE SET
            method=EXCLUDED.method,linked_at=EXCLUDED.linked_at,linked_by=EXCLUDED.linked_by
          WHERE user_source_identities.user_id=EXCLUDED.user_id AND
            (user_source_identities.method,user_source_identities.linked_at,user_source_identities.linked_by)
            IS DISTINCT FROM (EXCLUDED.method,EXCLUDED.linked_at,EXCLUDED.linked_by)
          """)
              .param("source", link.source().value())
              .param("id", link.sourceUserId())
              .param("user", link.userId())
              .param("method", wire(link.method()))
              .param("at", Timestamp.from(link.linkedAt()))
              .param("actor", link.linkedBy())
              .update();
      if (changed == 0
          && !link(link.source(), link.sourceUserId()).orElseThrow().userId().equals(link.userId()))
        throw new IllegalStateException("Identity already linked to another user");
      return changed > 0;
    }

    @Override
    public boolean removeLink(SourceInstanceId source, String id) {
      requireOpen();
      return jdbc.sql(
                  "DELETE FROM user_source_identities WHERE source_instance_id=:source AND source_user_id=:id")
              .param("source", source.value())
              .param("id", id)
              .update()
          > 0;
    }

    @Override
    public Page<IdentityLink> links(Optional<String> user, PageRequest page) {
      requireOpen();
      var after = page.after().map(value -> linkCursor(value));
      var rows =
          jdbc.sql(
                  """
          SELECT * FROM user_source_identities WHERE (:user IS NULL OR user_id=:user)
            AND (:source IS NULL OR (source_instance_id COLLATE "C",source_user_id COLLATE "C") > (:source,:id))
          ORDER BY source_instance_id COLLATE "C",source_user_id COLLATE "C" LIMIT :limit
          """)
              .param("user", user.orElse(null), Types.VARCHAR)
              .param("source", after.map(parts -> parts[0]).orElse(null), Types.VARCHAR)
              .param("id", after.map(parts -> parts[1]).orElse(null), Types.VARCHAR)
              .param("limit", page.limit() + 1)
              .query(PostgresAdminStore::linkRow)
              .list();
      return page(rows, page.limit(), "links", l -> l.source().value() + "\0" + l.sourceUserId());
    }

    private String[] linkCursor(String cursor) {
      var parts = decode("links", cursor).split("\0", -1);
      if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty())
        throw new IllegalArgumentException("Invalid link cursor");
      return parts;
    }

    @Override
    public boolean designate(ScopeDesignation scope) {
      requireOpen();
      return jdbc.sql(
                  "INSERT INTO org_wide_scopes(source_instance_id,designated_by,designated_at) VALUES(:source,:actor,:at) ON CONFLICT DO NOTHING")
              .param("source", scope.source().value())
              .param("actor", scope.designatedBy())
              .param("at", Timestamp.from(scope.designatedAt()))
              .update()
          > 0;
    }

    @Override
    public boolean revokeScope(SourceInstanceId source) {
      requireOpen();
      return jdbc.sql("DELETE FROM org_wide_scopes WHERE source_instance_id=:source")
              .param("source", source.value())
              .update()
          > 0;
    }

    @Override
    public List<ScopeDesignation> scopes() {
      requireOpen();
      return jdbc.sql("SELECT * FROM org_wide_scopes ORDER BY source_instance_id COLLATE \"C\"")
          .query(
              (rs, row) ->
                  new ScopeDesignation(
                      new SourceInstanceId(rs.getString("source_instance_id")),
                      rs.getString("designated_by"),
                      rs.getTimestamp("designated_at").toInstant()))
          .list();
    }

    @Override
    public UserTokens tokens() {
      requireOpen();
      return this;
    }

    @Override
    public Optional<StoredToken> token(UUID id) {
      requireOpen();
      return jdbc.sql("SELECT token_id,token_hash,user_id FROM user_tokens WHERE token_id=:id")
          .param("id", id)
          .query(PostgresAdminStore::storedTokenRow)
          .optional();
    }

    @Override
    public Optional<StoredToken> tokenByHash(TokenHash hash) {
      requireOpen();
      return jdbc.sql("SELECT token_id,token_hash,user_id FROM user_tokens WHERE token_hash=:hash")
          .param("hash", hash.value())
          .query(PostgresAdminStore::storedTokenRow)
          .optional();
    }

    @Override
    public Page<TokenMetadata> tokenPage(String user, PageRequest page) {
      requireOpen();
      var after = page.after().map(value -> decode("tokens", value));
      after.ifPresent(UUID::fromString);
      var rows =
          jdbc.sql(
                  """
          SELECT token_id,user_id,label,created_at,last_used_at,revoked_at FROM user_tokens
          WHERE user_id=:user AND (:after IS NULL OR token_id::text COLLATE "C" > :after)
          ORDER BY token_id::text COLLATE "C" LIMIT :limit
          """)
              .param("user", user)
              .param("after", after.orElse(null), Types.VARCHAR)
              .param("limit", page.limit() + 1)
              .query(PostgresAdminStore::metadataRow)
              .list();
      return page(rows, page.limit(), "tokens", t -> t.tokenId().toString());
    }

    @Override
    public void appendAudit(NewAuditEvent event) {
      requireOpen();
      jdbc.sql(
              "INSERT INTO audit_events(at,actor,action,target_type,target_id,detail) VALUES(:at,:actor,:action,:type,:id,:detail::jsonb)")
          .param("at", Timestamp.from(event.at()))
          .param("actor", event.actor(), Types.VARCHAR)
          .param("action", event.action())
          .param("type", event.targetType())
          .param("id", event.targetId())
          .param("detail", json.writeValueAsString(event.detail()))
          .update();
    }

    @Override
    public Page<AuditEvent> audit(AuditQuery query) {
      requireOpen();
      var after = query.after().map(value -> auditCursor(value));
      var rows =
          jdbc.sql(
                  """
          SELECT * FROM audit_events WHERE (:at::timestamptz IS NULL OR (at,id) < (:at,:id))
          AND (:action IS NULL OR action=:action) AND (:actor IS NULL OR actor=:actor)
          AND (:type IS NULL OR target_type=:type) AND (:target IS NULL OR target_id=:target)
          ORDER BY at DESC,id DESC LIMIT :limit
          """)
              .param(
                  "at",
                  after.map(parts -> Timestamp.from(Instant.parse(parts[0]))).orElse(null),
                  Types.TIMESTAMP)
              .param("id", after.map(parts -> Long.parseLong(parts[1])).orElse(null), Types.BIGINT)
              .param("action", query.action().orElse(null), Types.VARCHAR)
              .param("actor", query.actor().orElse(null), Types.VARCHAR)
              .param("type", query.targetType().orElse(null), Types.VARCHAR)
              .param("target", query.targetId().orElse(null), Types.VARCHAR)
              .param("limit", query.limit() + 1)
              .query(
                  (rs, row) ->
                      new AuditEvent(
                          rs.getLong("id"),
                          rs.getTimestamp("at").toInstant(),
                          rs.getString("actor"),
                          rs.getString("action"),
                          rs.getString("target_type"),
                          rs.getString("target_id"),
                          json.readValue(
                              rs.getString("detail"), new TypeReference<Map<String, String>>() {})))
              .list();
      return page(rows, query.limit(), "audit", e -> e.at() + "|" + e.id());
    }

    private String[] auditCursor(String cursor) {
      try {
        var parts = decode("audit", cursor).split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid audit cursor");
        Instant.parse(parts[0]);
        Long.parseLong(parts[1]);
        return parts;
      } catch (RuntimeException invalid) {
        throw new IllegalArgumentException("Invalid audit cursor", invalid);
      }
    }

    @Override
    public Optional<Principal> authenticate(TokenHash hash) {
      requireOpen();
      return tokens.authenticate(hash);
    }

    @Override
    public void store(TokenHash hash, String user, String label, Instant at) {
      requireOpen();
      tokens.store(hash, user, label, at);
    }

    @Override
    public void revoke(TokenHash hash, Instant at) {
      requireOpen();
      tokens.revoke(hash, at);
    }

    @Override
    public void revokeAllOf(String user, Instant at) {
      requireOpen();
      tokens.revokeAllOf(user, at);
    }

    @Override
    public void markUsed(Map<TokenHash, Instant> used) {
      requireOpen();
      tokens.markUsed(used);
    }
  }

  private static UserAccount userRow(ResultSet rs, int row) throws SQLException {
    return new UserAccount(
        rs.getString("id"),
        rs.getString("email"),
        rs.getBoolean("active"),
        Role.valueOf(rs.getString("role").toUpperCase(Locale.ROOT)),
        rs.getTimestamp("created_at").toInstant());
  }

  private static IdentityLink linkRow(ResultSet rs, int row) throws SQLException {
    return new IdentityLink(
        new SourceInstanceId(rs.getString("source_instance_id")),
        rs.getString("source_user_id"),
        rs.getString("user_id"),
        LinkMethod.valueOf(rs.getString("method").toUpperCase(Locale.ROOT)),
        rs.getTimestamp("linked_at").toInstant(),
        rs.getString("linked_by"),
        Set.of());
  }

  private static StoredToken storedTokenRow(ResultSet rs, int row) throws SQLException {
    return new StoredToken(
        rs.getObject("token_id", UUID.class),
        new TokenHash(rs.getString("token_hash")),
        rs.getString("user_id"));
  }

  private static TokenMetadata metadataRow(ResultSet rs, int row) throws SQLException {
    return new TokenMetadata(
        rs.getObject("token_id", UUID.class),
        rs.getString("user_id"),
        rs.getString("label"),
        rs.getTimestamp("created_at").toInstant(),
        Optional.ofNullable(rs.getTimestamp("last_used_at")).map(Timestamp::toInstant),
        Optional.ofNullable(rs.getTimestamp("revoked_at")).map(Timestamp::toInstant));
  }

  private String wire(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private <T> Page<T> page(List<T> rows, int limit, String kind, Function<T, String> key) {
    var items = rows.stream().limit(limit).toList();
    return new Page<>(
        items,
        rows.size() > limit
            ? Optional.of(encode(kind, key.apply(items.getLast())))
            : Optional.empty());
  }

  private String encode(String kind, String key) {
    return kind
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(key.getBytes(StandardCharsets.UTF_8));
  }

  private String decode(String kind, String cursor) {
    if (!cursor.startsWith(kind + ".")) throw new IllegalArgumentException("Invalid cursor");
    try {
      var key =
          new String(
              Base64.getUrlDecoder().decode(cursor.substring(kind.length() + 1)),
              StandardCharsets.UTF_8);
      if (key.isEmpty() || !encode(kind, key).equals(cursor))
        throw new IllegalArgumentException("Invalid cursor");
      validateCursor(kind, key);
      return key;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid cursor", invalid);
    }
  }

  private void validateCursor(String kind, String key) {
    switch (kind) {
      case "tokens" -> UUID.fromString(key);
      case "links" -> {
        var parts = key.split("\0", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty())
          throw new IllegalArgumentException("Invalid link cursor");
      }
      case "audit" -> {
        var parts = key.split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid audit cursor");
        Instant.parse(parts[0]);
        if (Long.parseLong(parts[1]) < 1)
          throw new IllegalArgumentException("Invalid audit cursor");
      }
      default -> {}
    }
  }
}
