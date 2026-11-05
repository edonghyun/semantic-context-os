package io.scos.store.postgres;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.source.SourceInstanceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 활성 사용자와 원천별 접근 키를 한 문장에서 읽는다. */
public final class PostgresIdentityStore implements IdentityStore {
  private static final String ACCESS_KEYS_SQL =
      """
      WITH me AS MATERIALIZED (
        SELECT u.id, u.email FROM users u WHERE u.id = :user AND u.active
      ), ids AS MATERIALIZED (
        SELECT si.source_instance_id, si.source_user_id
        FROM me JOIN user_source_identities l ON l.user_id = me.id
        JOIN source_identities si ON si.source_instance_id = l.source_instance_id
          AND si.source_user_id = l.source_user_id
        WHERE NOT si.guest AND NOT si.deleted
      ), direct AS MATERIALIZED (
        SELECT DISTINCT g.source_instance_id, g.group_id
        FROM ids i JOIN source_groups g ON g.source_instance_id = i.source_instance_id
          AND g.members && ARRAY[i.source_user_id]
      ), via AS (
        SELECT DISTINCT g.source_instance_id, g.group_id
        FROM direct d JOIN source_groups g ON g.source_instance_id = d.source_instance_id
          AND g.members && ARRAY[d.group_id]
        WHERE d.group_id LIKE '~%' AND g.group_id NOT LIKE '~%'
      ), user_keys AS (
        SELECT 'user' AS kind, source_instance_id AS instance, source_user_id AS value FROM ids
      ), group_keys AS (
        SELECT 'group' AS kind, source_instance_id AS instance, group_id AS value FROM direct
        UNION SELECT 'group', source_instance_id, group_id FROM via
      ), domain_keys AS (
        SELECT 'domain' AS kind, d.instance, d.domain AS value
        FROM me CROSS JOIN unnest(CAST(:instances AS text[]), CAST(:domains AS text[])) d(instance, domain)
        WHERE substring(me.email from '@([^@]*)$') = d.domain AND d.domain = ANY(:org_domains)
      ), scope_keys AS (
        SELECT 'scope' AS kind, source_instance_id AS instance, '*' AS value
        FROM me CROSS JOIN org_wide_scopes
      ), keys AS (
        SELECT * FROM user_keys UNION SELECT * FROM group_keys
        UNION SELECT * FROM domain_keys UNION SELECT * FROM scope_keys
      )
      SELECT keys.kind, keys.instance, keys.value FROM me LEFT JOIN keys ON TRUE
      """;
  private final JdbcClient jdbc;
  private final AccessKeyInstances instances = new AccessKeyInstances();
  private final System.Logger logger = System.getLogger(PostgresIdentityStore.class.getName());

  public PostgresIdentityStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Set<AccessKey>> accessKeysOf(String userId, DomainPolicy domains) {
    var instances = domains.instanceDomains().entrySet().stream().toList();
    var rows =
        jdbc.sql(ACCESS_KEYS_SQL)
            .param("user", userId)
            .param(
                "instances", instances.stream().map(e -> e.getKey().value()).toArray(String[]::new))
            .param("domains", instances.stream().map(e -> e.getValue()).toArray(String[]::new))
            .param("org_domains", domains.orgDomains().toArray(String[]::new))
            .query((rs, row) -> keyOf(rs))
            .list();
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(
        rows.stream().flatMap(Optional::stream).collect(Collectors.toUnmodifiableSet()));
  }

  private Optional<AccessKey> keyOf(ResultSet row) throws SQLException {
    String kind = row.getString("kind");
    // LEFT JOIN의 null 행은 키 없는 활성 사용자다. 실제 키의 두 열은 원천 PK와 정책에서 온다.
    if (kind == null) return Optional.empty();
    var instance = new SourceInstanceId(Objects.requireNonNull(row.getString("instance")));
    if (!instances.admits(instance, logger)) return Optional.empty();
    return Optional.of(
        new AccessKey(
            AccessKey.Kind.valueOf(kind.toUpperCase(Locale.ROOT)),
            instance,
            Objects.requireNonNull(row.getString("value"))));
  }
}
