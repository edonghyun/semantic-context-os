package io.scos.store.postgres;

import io.scos.collection.internal.postgres.MigrationRehearsal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 접근 코어 이전 형태의 데이터와 마이그레이션 후 관측을 맡는다. */
@Tag("db")
final class AccessCoreMigrationScenario {
  private static final String V_BEFORE_CORE = "22";
  private static final int UNIT_TOKENS = 5;
  private static final int UNIT_IMPORTANCE = 5;
  private final MigrationRehearsal rehearsal;
  private final JdbcClient jdbc;

  AccessCoreMigrationScenario(String database) {
    rehearsal = MigrationRehearsal.freshDatabase(database).migrateTo(V_BEFORE_CORE);
    jdbc = rehearsal.jdbc();
  }

  void givenOwner(String documentId, String space) {
    jdbc.sql("INSERT INTO document_spaces (document_id, space_id) VALUES (?, ?)")
        .params(documentId, space)
        .update();
  }

  void givenUnit(String documentId, String space, String sourceId) {
    jdbc.sql(
            """
        INSERT INTO units (space_id, id, document_id, partition, title, content, content_hash,
                           tokens, tier, source_id, uri, captured_at, importance)
        VALUES (:space, :id, :doc, '/knowledge', '제목', '본문', 'hash',
                :tokens, 'raw', :source, 'fixture://doc', '2026-10-26T00:00:00Z', :importance)
        """)
        .param("space", space)
        .param("id", documentId + ":" + space + ":" + sourceId)
        .param("doc", documentId)
        .param("tokens", UNIT_TOKENS)
        .param("source", sourceId)
        .param("importance", UNIT_IMPORTANCE)
        .update();
  }

  void migrate() {
    rehearsal.migrateTo(AccessCoreMigrationTest.V_CORE);
  }

  List<String> publicTables() {
    return jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")
        .query(String.class)
        .list();
  }

  Optional<String> sourceOf(String documentId) {
    return jdbc.sql("SELECT source_id FROM document_spaces WHERE document_id = ?")
        .param(documentId)
        .query(String.class)
        .optional();
  }

  List<String> ownedDocuments() {
    return jdbc.sql("SELECT document_id FROM document_spaces ORDER BY document_id")
        .query(String.class)
        .list();
  }

  Snapshot snapshot() {
    return new Snapshot(
        jdbc.sql("SELECT row_to_json(d)::text FROM document_spaces d ORDER BY document_id")
            .query(String.class)
            .list(),
        jdbc.sql("SELECT row_to_json(u)::text FROM units u ORDER BY seq")
            .query(String.class)
            .list(),
        jdbc.sql(
                "SELECT table_name || ':' || column_name || ':' || is_nullable FROM information_schema.columns WHERE table_schema = 'public' ORDER BY table_name, ordinal_position")
            .query(String.class)
            .list(),
        jdbc.sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank")
            .query(String.class)
            .list());
  }

  String sourceNullable() {
    return jdbc.sql(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'document_spaces' AND column_name = 'source_id'")
        .query(String.class)
        .single();
  }

  record Snapshot(
      List<String> owners, List<String> units, List<String> schema, List<String> versions) {}
}
