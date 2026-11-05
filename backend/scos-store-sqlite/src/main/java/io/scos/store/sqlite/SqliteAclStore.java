package io.scos.store.sqlite;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.store.SpaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.transaction.support.TransactionOperations;

/** SQLite 문서 ACL(V1 {@code document_acl}). 키는 정렬한 JSON 배열 텍스트로 저장한다. */
public final class SqliteAclStore implements AclStore {
  /** 문자열 비교가 시각 순서와 같도록 소수부를 9자리로 고정한다. */
  private static final DateTimeFormatter TIME =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
          .appendFraction(ChronoField.NANO_OF_SECOND, 9, 9, true)
          .appendLiteral('Z')
          .toFormatter(Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private final JdbcClient jdbc;
  private final TransactionOperations tx;

  public SqliteAclStore(JdbcClient jdbc, TransactionOperations tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public boolean hasAny() {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM document_acl)").query(Boolean.class).single();
  }

  /** 호출자의 트랜잭션에 참여하고, 없으면 한 트랜잭션으로 묶어 넣는다. */
  @Override
  public void put(SpaceId space, List<DocumentAcl> acls) {
    if (acls.isEmpty()) return;
    tx.executeWithoutResult(
        status -> {
          for (DocumentAcl acl : acls) upsert(space, acl);
        });
  }

  private void upsert(SpaceId space, DocumentAcl acl) {
    List<String> keys = acl.keys().stream().map(AccessKey::toString).sorted().toList();
    StatementSpec statement =
        jdbc.sql(
            "INSERT INTO document_acl (space_id, document_id, status, keys, fingerprint, observed_at, reason)"
                + " VALUES (:space, :doc, :status, :keys, :fingerprint, :observed, :reason)"
                + " ON CONFLICT (space_id, document_id) DO UPDATE SET"
                + " status = excluded.status, keys = excluded.keys,"
                + " fingerprint = excluded.fingerprint, observed_at = excluded.observed_at, reason = excluded.reason"
                + " WHERE (document_acl.fingerprint <> excluded.fingerprint OR document_acl.reason <> excluded.reason)"
                + " AND document_acl.observed_at < excluded.observed_at");
    statement
        .param("space", space.value())
        .param("doc", acl.documentId())
        .param("status", acl.status().name().toLowerCase(Locale.ROOT))
        .param("keys", SqlParts.json(keys))
        .param("fingerprint", acl.fingerprint())
        .param("observed", TIME.format(acl.observedAt()))
        .param("reason", acl.reason().name());
    statement.update();
  }

  @Override
  public Map<String, DocumentAcl> get(SpaceId space, Collection<String> documentIds) {
    if (documentIds.isEmpty()) return Map.of();
    Map<String, DocumentAcl> out = new HashMap<>();
    jdbc.sql(
            "SELECT document_id, status, keys, observed_at, reason FROM document_acl"
                + " WHERE space_id = :space AND document_id IN (SELECT value FROM json_each(:docs))")
        .param("space", space.value())
        .param("docs", SqlParts.json(documentIds))
        .query((rs, row) -> row(rs.getString("document_id"), rs))
        .list()
        .forEach(acl -> out.put(acl.documentId(), acl));
    return Map.copyOf(out);
  }

  private DocumentAcl row(String documentId, ResultSet rs) throws SQLException {
    List<String> keyTexts = SqlParts.strings(rs.getString("keys"));
    return new DocumentAcl(
        documentId,
        DocumentAcl.Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
        keyTexts.stream().map(AccessKey::parse).collect(Collectors.toUnmodifiableSet()),
        Instant.from(TIME.parse(rs.getString("observed_at"))),
        AccessFailureReason.fromStored(rs.getString("reason")));
  }

  @Override
  public void delete(SpaceId space, Collection<String> documentIds) {
    if (documentIds.isEmpty()) return;
    tx.executeWithoutResult(
        status ->
            jdbc.sql(
                    "DELETE FROM document_acl WHERE space_id = :space"
                        + " AND document_id IN (SELECT value FROM json_each(:docs))")
                .param("space", space.value())
                .param("docs", SqlParts.json(documentIds))
                .update());
  }
}
