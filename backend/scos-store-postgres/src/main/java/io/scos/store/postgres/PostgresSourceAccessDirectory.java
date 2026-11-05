package io.scos.store.postgres;

import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 그룹·신원 표의 행 모양과 지문 저장을 소유하며 호출자 트랜잭션에 참여한다. */
public final class PostgresSourceAccessDirectory implements SourceAccessDirectory {
  private final JdbcClient jdbc;

  public PostgresSourceAccessDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Snapshot current(SourceInstanceId source) {
    var identities =
        jdbc.sql("SELECT * FROM source_identities WHERE source_instance_id = :source FOR UPDATE")
            .param("source", source.value())
            .query(
                (rs, row) ->
                    new SourceIdentityRecord(
                        rs.getString("source_user_id"),
                        Optional.ofNullable(rs.getString("email")),
                        rs.getBoolean("email_verified"),
                        rs.getBoolean("guest"),
                        rs.getBoolean("deleted"),
                        rs.getTimestamp("observed_at").toInstant()))
            .list();
    var groups =
        jdbc.sql("SELECT * FROM source_groups WHERE source_instance_id = :source FOR UPDATE")
            .param("source", source.value())
            .query(
                (rs, row) ->
                    new GroupSnapshot(
                        rs.getString("group_id"),
                        Set.copyOf(Arrays.asList((String[]) rs.getArray("members").getArray())),
                        rs.getTimestamp("observed_at").toInstant(),
                        rs.getString("group_id").startsWith("~")
                            ? GroupSnapshot.Kind.SYNTHETIC
                            : GroupSnapshot.Kind.REAL))
            .list();
    return new Snapshot(
        identities.stream().collect(Collectors.toMap(SourceIdentityRecord::sourceUserId, i -> i)),
        groups.stream().collect(Collectors.toMap(GroupSnapshot::groupId, g -> g)));
  }

  @Override
  public void save(SourceInstanceId source, Snapshot snapshot) {
    for (SourceIdentityRecord identity : snapshot.identities().values())
      persistIdentity(source, identity);
    for (GroupSnapshot group : snapshot.groups().values()) persistGroup(source, group);
  }

  private void persistIdentity(SourceInstanceId source, SourceIdentityRecord identity) {
    jdbc.sql(
            """
          INSERT INTO source_identities (source_instance_id, source_user_id, email, email_verified, guest, deleted, observed_at)
          VALUES (:source, :id, :email, :verified, :guest, :deleted, :at)
          ON CONFLICT (source_instance_id, source_user_id) DO UPDATE SET
            email = EXCLUDED.email, email_verified = EXCLUDED.email_verified, guest = EXCLUDED.guest,
            deleted = EXCLUDED.deleted, observed_at = EXCLUDED.observed_at
          WHERE (source_identities.email, source_identities.email_verified, source_identities.guest,
                 source_identities.deleted, source_identities.observed_at)
             IS DISTINCT FROM (EXCLUDED.email, EXCLUDED.email_verified, EXCLUDED.guest,
                               EXCLUDED.deleted, EXCLUDED.observed_at)
          """)
        .param("source", source.value())
        .param("id", identity.sourceUserId())
        .param("email", identity.email().orElse(null), Types.VARCHAR)
        .param("verified", identity.emailVerified())
        .param("guest", identity.guest())
        .param("deleted", identity.deleted())
        .param("at", Timestamp.from(identity.observedAt()))
        .update();
  }

  private void persistGroup(SourceInstanceId source, GroupSnapshot group) {
    jdbc.sql(
            """
          INSERT INTO source_groups (source_instance_id, group_id, members, fingerprint, observed_at)
          VALUES (:source, :id, :members, :fingerprint, :at)
          ON CONFLICT (source_instance_id, group_id) DO UPDATE SET
            members = EXCLUDED.members, fingerprint = EXCLUDED.fingerprint, observed_at = EXCLUDED.observed_at
          WHERE (source_groups.fingerprint, source_groups.observed_at)
             IS DISTINCT FROM (EXCLUDED.fingerprint, EXCLUDED.observed_at)
          """)
        .param("source", source.value())
        .param("id", group.groupId())
        .param("members", group.members().stream().sorted().toArray(String[]::new))
        .param("fingerprint", group.fingerprint())
        .param("at", Timestamp.from(group.observedAt()))
        .update();
  }
}
