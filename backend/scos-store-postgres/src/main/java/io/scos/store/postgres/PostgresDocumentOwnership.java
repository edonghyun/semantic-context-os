package io.scos.store.postgres;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceOwnershipException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 같은 소유는 다시 쓰지 않는다. 호출자 tx의 ACL 읽기·결정을 직렬화하도록 기존 소유도 배타 잠금한다. */
public final class PostgresDocumentOwnership implements DocumentOwnership {
  private final JdbcClient jdbc;

  public PostgresDocumentOwnership(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Claim claim(SpaceId space, String sourceId, String documentId) {
    jdbc.sql(
            """
        INSERT INTO document_spaces (document_id, space_id, source_id)
        VALUES (:doc, :space, :source) ON CONFLICT (document_id) DO NOTHING
        """)
        .param("doc", documentId)
        .param("space", space.value())
        .param("source", sourceId)
        .update();
    Owner owner =
        jdbc.sql(
                """
        SELECT space_id, source_id FROM document_spaces WHERE document_id = :doc FOR NO KEY UPDATE
        """)
            .param("doc", documentId)
            .query(
                (rs, row) ->
                    new Owner(new SpaceId(rs.getString("space_id")), rs.getString("source_id")))
            .single();
    return classify(space, sourceId, documentId, owner);
  }

  /** 문서 순으로 등록하고 잠근다. 호출자 트랜잭션이 배치 전체의 원자성을 소유한다. */
  void claimAll(SpaceId space, List<ContextUnit> units) {
    var requested = requestedOwners(units);
    if (requested.isEmpty()) return;
    jdbc.sql(
            """
        INSERT INTO document_spaces (document_id, space_id, source_id)
        SELECT document_id, :space, source_id
        FROM unnest(CAST(:documents AS text[]), CAST(:sources AS text[])) AS r(document_id, source_id)
        ORDER BY document_id ON CONFLICT (document_id) DO NOTHING
        """)
        .param("space", space.value())
        .param("documents", requested.keySet().toArray(String[]::new))
        .param("sources", requested.values().toArray(String[]::new))
        .update();
    var owners =
        jdbc.sql(
                """
        SELECT document_id, space_id, source_id FROM document_spaces
        WHERE document_id = ANY(:documents) ORDER BY document_id FOR NO KEY UPDATE
        """)
            .param("documents", requested.keySet().toArray(String[]::new))
            .query(
                (rs, row) ->
                    Map.entry(
                        rs.getString("document_id"),
                        new Owner(
                            new SpaceId(rs.getString("space_id")), rs.getString("source_id"))))
            .list();
    if (owners.size() != requested.size())
      throw new IllegalStateException(
          "문서 소유 잠금 누락: 요청 " + requested.size() + ", 잠금 " + owners.size());
    for (var owner : owners)
      if (classify(
              space,
              Objects.requireNonNull(requested.get(owner.getKey())),
              owner.getKey(),
              owner.getValue())
          == Claim.OTHER_SOURCE)
        throw new IllegalArgumentException("다른 출처 소유 문서의 쓰기 거부: " + owner.getKey());
  }

  private Map<String, String> requestedOwners(List<ContextUnit> units) {
    var requested = new TreeMap<String, String>();
    for (ContextUnit unit : units) {
      String document = unit.documentId();
      String source = unit.provenance().sourceId();
      var previous = requested.putIfAbsent(document, source);
      if (previous != null && !previous.equals(source))
        throw new IllegalArgumentException("다른 출처 소유 문서의 쓰기 거부: " + document);
    }
    return requested;
  }

  private Claim classify(SpaceId space, String sourceId, String documentId, Owner owner) {
    if (!owner.space().equals(space))
      throw new SpaceOwnershipException(documentId, owner.space(), space);
    return owner.sourceId().equals(sourceId) ? Claim.OWNED : Claim.OTHER_SOURCE;
  }

  @Override
  public Optional<SpaceId> ownedSpace(String sourceId, String documentId) {
    return jdbc.sql(
            "SELECT space_id FROM document_spaces WHERE source_id = :source AND document_id = :doc")
        .param("source", sourceId)
        .param("doc", documentId)
        .query((rs, row) -> new SpaceId(rs.getString("space_id")))
        .optional();
  }

  @Override
  public Set<String> listDocumentIds(SpaceId space, String sourceId) {
    return Set.copyOf(
        jdbc.sql(
                """
            SELECT document_id FROM document_spaces WHERE space_id = :space AND source_id = :source
            """)
            .param("space", space.value())
            .param("source", sourceId)
            .query(String.class)
            .list());
  }

  private record Owner(SpaceId space, String sourceId) {}
}
