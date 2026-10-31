package io.scos.store.sqlite;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.connector.SourceInstanceId;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 활성 사용자와 원천별 접근 키를 읽는다(V1 users·source_identities·source_groups…). 멤버 목록은 JSON 배열 텍스트다. */
public final class SqliteIdentityStore implements IdentityStore {
  /** 사용자 키(user)·직접 그룹·합성 그룹을 거친 그룹(한 단계)·지정 범위(scope)를 한 문장으로 읽는다. */
  private static final String KEYS_SQL =
      """
      WITH ids AS MATERIALIZED (
        SELECT si.source_instance_id, si.source_user_id
        FROM user_source_identities l
        JOIN source_identities si ON si.source_instance_id = l.source_instance_id
          AND si.source_user_id = l.source_user_id
        WHERE l.user_id = :user AND si.guest = 0 AND si.deleted = 0
      ), direct AS MATERIALIZED (
        SELECT DISTINCT g.source_instance_id, g.group_id
        FROM ids i JOIN source_groups g ON g.source_instance_id = i.source_instance_id
        WHERE EXISTS (SELECT 1 FROM json_each(g.members) m WHERE m.value = i.source_user_id)
      ), via AS (
        SELECT DISTINCT g.source_instance_id, g.group_id
        FROM direct d JOIN source_groups g ON g.source_instance_id = d.source_instance_id
        WHERE substr(d.group_id, 1, 1) = '~' AND substr(g.group_id, 1, 1) <> '~'
          AND EXISTS (SELECT 1 FROM json_each(g.members) m WHERE m.value = d.group_id)
      )
      SELECT 'user' AS kind, source_instance_id AS instance, source_user_id AS value FROM ids
      UNION SELECT 'group', source_instance_id, group_id FROM direct
      UNION SELECT 'group', source_instance_id, group_id FROM via
      UNION SELECT 'scope', source_instance_id, '*' FROM org_wide_scopes
      """;

  private final JdbcClient jdbc;
  private final AccessKeyInstances instances = new AccessKeyInstances();
  private final System.Logger logger = System.getLogger(SqliteIdentityStore.class.getName());

  public SqliteIdentityStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Set<AccessKey>> accessKeysOf(String userId, DomainPolicy domains) {
    Optional<String> email =
        jdbc.sql("SELECT email FROM users WHERE id = :user AND active = 1")
            .param("user", userId)
            .query(String.class)
            .optional();
    if (email.isEmpty()) return Optional.empty();
    Set<AccessKey> keys = new HashSet<>();
    jdbc.sql(KEYS_SQL)
        .param("user", userId)
        .query(
            (rs, row) -> {
              var instance = new SourceInstanceId(rs.getString("instance"));
              if (instances.admits(instance, logger))
                keys.add(
                    new AccessKey(
                        AccessKey.Kind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
                        instance,
                        rs.getString("value")));
              return 0;
            })
        .list();
    domains
        .instanceDomains()
        .forEach(
            (instance, domain) -> {
              if (instances.admits(instance, logger) && domains.admits(email.get(), instance))
                keys.add(new AccessKey(AccessKey.Kind.DOMAIN, instance, domain));
            });
    return Optional.of(Set.copyOf(keys));
  }
}
