package io.scos.store.postgres;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.store.SpaceId;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 문서 ACL 저장. 트랜잭션은 호출자가 소유하며 같은 DataSource의 연결에 참여한다. */
public final class PostgresAclStore implements AclStore {
  private final JdbcClient jdbc;

  public PostgresAclStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean hasAny() {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM document_acl)").query(Boolean.class).single();
  }

  @Override
  public void put(SpaceId space, List<DocumentAcl> acls) {
    for (DocumentAcl acl : acls)
      jdbc.sql(
              """
          INSERT INTO document_acl (space_id, document_id, status, keys, fingerprint, observed_at)
          VALUES (:space, :doc, :status, :keys, :fingerprint, :observed)
          ON CONFLICT (space_id, document_id) DO UPDATE SET
            status = EXCLUDED.status, keys = EXCLUDED.keys,
            fingerprint = EXCLUDED.fingerprint, observed_at = EXCLUDED.observed_at
          WHERE document_acl.fingerprint <> EXCLUDED.fingerprint
            AND document_acl.observed_at < EXCLUDED.observed_at
          """)
          .param("space", space.value())
          .param("doc", acl.documentId())
          .param("status", acl.status().name().toLowerCase(Locale.ROOT))
          .param(
              "keys", acl.keys().stream().map(AccessKey::toString).sorted().toArray(String[]::new))
          .param("fingerprint", acl.fingerprint())
          .param("observed", Timestamp.from(acl.observedAt()))
          .update();
  }

  @Override
  public Map<String, DocumentAcl> get(SpaceId space, Collection<String> documentIds) {
    if (documentIds.isEmpty()) return Map.of();
    return jdbc
        .sql(
            """
        SELECT document_id, status, keys, observed_at FROM document_acl
        WHERE space_id = :space AND document_id = ANY(:docs)
        """)
        .param("space", space.value())
        .param("docs", documentIds.toArray(String[]::new))
        .query(
            (rs, row) ->
                new DocumentAcl(
                    rs.getString("document_id"),
                    DocumentAcl.Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                    Arrays.stream((String[]) rs.getArray("keys").getArray())
                        .map(AccessKey::parse)
                        .collect(Collectors.toUnmodifiableSet()),
                    rs.getTimestamp("observed_at").toInstant()))
        .list()
        .stream()
        .collect(Collectors.toUnmodifiableMap(DocumentAcl::documentId, acl -> acl));
  }

  @Override
  public void delete(SpaceId space, Collection<String> documentIds) {
    if (documentIds.isEmpty()) return;
    jdbc.sql("DELETE FROM document_acl WHERE space_id = :space AND document_id = ANY(:docs)")
        .param("space", space.value())
        .param("docs", documentIds.toArray(String[]::new))
        .update();
  }
}
