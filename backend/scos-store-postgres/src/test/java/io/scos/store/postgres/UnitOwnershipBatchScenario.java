package io.scos.store.postgres;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.SpaceId;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 실제 PostgreSQL의 문장 트리거로 소유 확인 왕복을 관측한다. */
@Tag("db")
final class UnitOwnershipBatchScenario implements AutoCloseable {
  private final JdbcClient jdbc;
  private final PostgresUnitStore units;

  UnitOwnershipBatchScenario() {
    PostgresSupport.truncateAll();
    var ds = PostgresSupport.dataSource();
    jdbc = JdbcClient.create(ds);
    units = PostgresTestAdapters.unitStore(ds);
    jdbc.sql("CREATE TABLE unit_ownership_statements (n integer)").update();
    jdbc.sql(
            """
        CREATE FUNCTION count_unit_ownership_statements() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN INSERT INTO unit_ownership_statements VALUES (1); RETURN NULL; END $$
        """)
        .update();
    jdbc.sql(
            """
        CREATE TRIGGER count_unit_ownership_statements AFTER INSERT ON document_spaces
        FOR EACH STATEMENT EXECUTE FUNCTION count_unit_ownership_statements()
        """)
        .update();
  }

  void upsertRepeatedDocument() {
    units.upsert(
        new SpaceId("a"),
        List.of(
            ContextUnits.unit("u1").inDocument("d2").build(),
            ContextUnits.unit("u2").inDocument("d1").build(),
            ContextUnits.unit("u3").inDocument("d2").build()));
  }

  int ownershipStatements() {
    return jdbc.sql("SELECT count(*) FROM unit_ownership_statements").query(Integer.class).single();
  }

  @Override
  public void close() {
    jdbc.sql("DROP TRIGGER count_unit_ownership_statements ON document_spaces").update();
    jdbc.sql("DROP FUNCTION count_unit_ownership_statements()").update();
    jdbc.sql("DROP TABLE unit_ownership_statements").update();
  }
}
