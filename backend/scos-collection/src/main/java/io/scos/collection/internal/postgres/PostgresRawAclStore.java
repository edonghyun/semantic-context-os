package io.scos.collection.internal.postgres;

import io.scos.collection.internal.RawAclDecision;
import io.scos.collection.internal.RawAclStore;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** 최초 삽입 경합과 기존 행 잠금을 같은 트랜잭션에 두고 호출자의 트랜잭션에도 참여한다. */
public final class PostgresRawAclStore implements RawAclStore {
  private final JdbcClient jdbc;
  private final TransactionOperations tx;

  public PostgresRawAclStore(JdbcClient jdbc, TransactionOperations tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public boolean observe(
      SourceInstanceId source, String externalId, RawAcl acl, Instant fallbackAt) {
    RawAclDecision initial = RawAclDecision.of(source, Optional.empty(), acl, fallbackAt);
    return Boolean.TRUE.equals(
        tx.execute(status -> observeLocked(source, externalId, acl, fallbackAt, initial)));
  }

  private boolean observeLocked(
      SourceInstanceId source, String id, RawAcl acl, Instant fallbackAt, RawAclDecision initial) {
    if (insertIfAbsent(source, id, initial)) return true;
    RawAcl.Observed previous =
        jdbc.sql(
                """
        SELECT status, keys, observed_at, reason FROM raw_acl
        WHERE source_instance_id = :source AND external_id = :id FOR UPDATE
        """)
            .param("source", source.value())
            .param("id", id)
            .query((rs, row) -> observed(rs, ""))
            .single();
    RawAclDecision decision = RawAclDecision.of(source, Optional.of(previous), acl, fallbackAt);
    boolean changed = decision.semanticChange() || decision.next().reason() != previous.reason();
    if (changed) {
      bind(
              jdbc.sql(
                  """
          UPDATE raw_acl SET status = :status, keys = :keys, fingerprint = :fingerprint, observed_at = :observed, reason = :reason
          WHERE source_instance_id = :source AND external_id = :id
          """),
              source,
              id,
              decision)
          .update();
    } else if (decision.next().observedAt().isAfter(previous.observedAt())) {
      // 같은 의미의 관측은 의미 열을 쓰지 않고 지연 응답 거부용 고수위만 전진한다.
      jdbc.sql(
              "UPDATE raw_acl SET observed_at = :observed WHERE source_instance_id = :source AND external_id = :id")
          .param("observed", Timestamp.from(decision.next().observedAt()))
          .param("source", source.value())
          .param("id", id)
          .update();
    }
    return changed;
  }

  private boolean insertIfAbsent(SourceInstanceId source, String id, RawAclDecision initial) {
    int inserted =
        bind(
                jdbc.sql(
                    """
        INSERT INTO raw_acl (source_instance_id, external_id, status, keys, fingerprint, observed_at, reason)
        VALUES (:source, :id, :status, :keys, :fingerprint, :observed, :reason)
        ON CONFLICT (source_instance_id, external_id) DO NOTHING
        """),
                source,
                id,
                initial)
            .update();
    return inserted > 0;
  }

  private JdbcClient.StatementSpec bind(
      JdbcClient.StatementSpec statement,
      SourceInstanceId source,
      String id,
      RawAclDecision decision) {
    RawAcl.Observed acl = decision.next();
    return statement
        .param("source", source.value())
        .param("id", id)
        .param("status", acl.status().name().toLowerCase(Locale.ROOT))
        .param("keys", acl.keys().stream().map(AccessKey::toString).sorted().toArray(String[]::new))
        .param(
            "fingerprint",
            new DocumentAcl(id, acl.status(), acl.keys(), acl.observedAt()).fingerprint())
        .param("observed", Timestamp.from(acl.observedAt()))
        .param("reason", acl.reason().name());
  }

  @Override
  public Map<String, RawAcl.Observed> get(SourceInstanceId source, Collection<String> externalIds) {
    if (externalIds.isEmpty()) return Map.of();
    return jdbc
        .sql(
            """
        SELECT external_id, status, keys, observed_at, reason FROM raw_acl
        WHERE source_instance_id = :source AND external_id = ANY(:ids)
        """)
        .param("source", source.value())
        .param("ids", externalIds.toArray(String[]::new))
        .query((rs, row) -> Map.entry(rs.getString("external_id"), observed(rs, "")))
        .list()
        .stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  public int documentsMissingAcl(SpaceId space, SourceInstanceId source) {
    return jdbc.sql(
            """
        SELECT count(DISTINCT u.document_id)::int FROM units u
        WHERE u.space_id = :space AND u.source_id = :source
          AND NOT EXISTS (SELECT 1 FROM document_acl a
                          WHERE a.space_id = u.space_id AND a.document_id = u.document_id)
        """)
        .param("space", space.value())
        .param("source", source.value())
        .query(Integer.class)
        .single();
  }

  @Override
  public void delete(SourceInstanceId source, Collection<String> externalIds) {
    if (externalIds.isEmpty()) return;
    jdbc.sql("DELETE FROM raw_acl WHERE source_instance_id = :source AND external_id = ANY(:ids)")
        .param("source", source.value())
        .param("ids", externalIds.toArray(String[]::new))
        .update();
  }

  static RawAcl.Observed observed(ResultSet rs, String prefix) throws SQLException {
    return new RawAcl.Observed(
        Status.valueOf(rs.getString(prefix + "status").toUpperCase(Locale.ROOT)),
        Arrays.stream((String[]) rs.getArray(prefix + "keys").getArray())
            .map(AccessKey::parse)
            .collect(Collectors.toUnmodifiableSet()),
        rs.getTimestamp(prefix + "observed_at").toInstant(),
        AccessFailureReason.fromStored(rs.getString(prefix + "reason")));
  }
}
