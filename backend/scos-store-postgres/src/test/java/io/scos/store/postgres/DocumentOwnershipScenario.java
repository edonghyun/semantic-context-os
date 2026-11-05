package io.scos.store.postgres;

import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 문서 소유 출처 명세의 저장·관측 연결. */
@Tag("db")
final class DocumentOwnershipScenario {
  private final JdbcClient jdbc;
  private final UnitMaintenance store;
  private final DocumentOwnership ownership;
  private final TransactionTemplate tx;

  DocumentOwnershipScenario() {
    PostgresSupport.truncateAll();
    var dataSource = PostgresSupport.dataSource();
    jdbc = JdbcClient.create(dataSource);
    ownership = new PostgresIndexConfiguration().newDocumentOwnership(dataSource);
    tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    var template = new NamedParameterJdbcTemplate(dataSource);
    store =
        new PostgresUnitMaintenance(
            new PostgresUnitStore(template, tx), new PostgresVectorIndex(template));
  }

  Optional<String> competingClaim() {
    return competingWrite(() -> ownership.claim(new SpaceId("a"), "source-a", "doc-1"));
  }

  Optional<String> competingUnitWrite() {
    return competingWrite(
        () ->
            store.upsert(
                new SpaceId("a"),
                List.of(ContextUnits.unit("u1").inDocument("doc-1").source("source-a").build())));
  }

  private Optional<String> competingWrite(Runnable write) {
    ownership.claim(new SpaceId("a"), "source-a", "doc-1");
    return tx.execute(
        status -> {
          ownership.claim(new SpaceId("a"), "source-a", "doc-1");
          return attemptOtherTransaction(write);
        });
  }

  private Optional<String> attemptOtherTransaction(Runnable write) {
    var other = new TransactionTemplate(tx.getTransactionManager());
    other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    try {
      other.executeWithoutResult(
          status -> {
            jdbc.sql("SET LOCAL lock_timeout = '200ms'").update();
            write.run();
          });
      return Optional.empty();
    } catch (DataAccessException locked) {
      if (locked.getMostSpecificCause() instanceof SQLException sql)
        return Optional.of(sql.getSQLState());
      throw locked;
    }
  }

  void claimBatchWithMissingOwner() {
    tx.executeWithoutResult(
        status -> {
          status.setRollbackOnly();
          jdbc.sql(
                  """
              CREATE FUNCTION pg_temp.omit_owner() RETURNS trigger LANGUAGE plpgsql AS
              $$ BEGIN RETURN NULL; END $$
              """)
              .update();
          jdbc.sql(
                  """
              CREATE TRIGGER omit_owner BEFORE INSERT ON document_spaces
              FOR EACH ROW EXECUTE FUNCTION pg_temp.omit_owner()
              """)
              .update();
          new PostgresDocumentOwnership(jdbc)
              .claimAll(
                  new SpaceId("a"),
                  List.of(ContextUnits.unit("u1").inDocument("doc-1").source("source-a").build()));
        });
  }

  void rollBackClaim() {
    tx.executeWithoutResult(
        status -> {
          ownership.claim(new SpaceId("a"), "source-a", "doc-1");
          status.setRollbackOnly();
        });
  }

  void rollBackUnits() {
    tx.executeWithoutResult(
        status -> {
          store.upsert(
              new SpaceId("a"),
              List.of(ContextUnits.unit("u1").inDocument("doc-1").source("source-a").build()));
          status.setRollbackOnly();
        });
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
