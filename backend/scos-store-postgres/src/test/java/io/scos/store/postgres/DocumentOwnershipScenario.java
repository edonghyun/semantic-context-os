package io.scos.store.postgres;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.UnitMaintenance;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 문서 소유 출처 명세의 저장·관측 연결. */
@Tag("db")
final class DocumentOwnershipScenario {
  private final JdbcClient jdbc;
  private final UnitMaintenance store;

  DocumentOwnershipScenario() {
    PostgresSupport.truncateAll();
    var dataSource = PostgresSupport.dataSource();
    jdbc = JdbcClient.create(dataSource);
    var template = new NamedParameterJdbcTemplate(dataSource);
    store =
        new PostgresUnitMaintenance(
            new PostgresUnitStore(template), new PostgresVectorIndex(template));
  }

  UnitMaintenance store() {
    return store;
  }

  Optional<String> sourceOf(String documentId) {
    return jdbc.sql("SELECT source_id FROM document_spaces WHERE document_id = ?")
        .param(documentId)
        .query(String.class)
        .optional();
  }
}
