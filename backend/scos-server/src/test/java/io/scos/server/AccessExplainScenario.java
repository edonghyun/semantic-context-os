package io.scos.server;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** 운영 조립의 조회·ACL·신원 저장을 읽기 전용 트랜잭션에서 함께 실행한다. */
@Tag("db")
final class AccessExplainScenario {
  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  private static final AccessKey READER = AccessKey.parse("user:drive-a:reader@example.com");
  private final DataSource dataSource = PostgresSupport.dataSource();
  private final MutableClock clock = new MutableClock(NOW);
  private final AclStore acls = new PostgresIndexConfiguration().newAclStore(dataSource);
  private final SourceAdapters registry =
      SourceAdapters.of(
          List.of(GoogleDriveAssembly.adapter("http://127.0.0.1:1", "synthetic", clock)));

  AccessExplainScenario() {
    PostgresSupport.truncateAll();
    var stores = new PostgresCollectionConfiguration().newAdapters(dataSource, registry);
    stores.sources().save(SourceInstances.of(SOURCE, "google-drive", "{}", "env:FAKE", NOW));
    new PostgresIndexConfiguration()
        .newDocumentOwnership(dataSource)
        .claim(SpaceId.LEGACY, SOURCE.value(), "file:F1");
    stores.rawItems().apply(SOURCE, RawItems.item("file:F1").resourceType("gdrive.file").upsert());
    stores
        .rawAcls()
        .observe(SOURCE, "file:F1", RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, NOW), NOW);
    acls.put(
        SpaceId.LEGACY,
        List.of(
            new DocumentAcl(
                "file:F1",
                DocumentAcl.Status.FAILED,
                Set.of(),
                NOW,
                AccessFailureReason.ACL_UNREADABLE)));
    givenReaderIdentity();
  }

  private void givenReaderIdentity() {
    var jdbc = JdbcClient.create(dataSource);
    jdbc.sql(
            "INSERT INTO users VALUES ('reader','reader@example.com',true,'member','2026-10-14T00:00:00Z')")
        .update();
    jdbc.sql(
            "INSERT INTO source_identities VALUES ('drive-a','reader@example.com','reader@example.com',true,false,false,'2026-10-14T00:00:00Z')")
        .update();
    jdbc.sql(
            "INSERT INTO user_source_identities VALUES ('drive-a','reader@example.com','reader','admin','2026-10-14T00:00:00Z','fixture')")
        .update();
  }

  void allowedDocument() {
    acls.put(
        SpaceId.LEGACY,
        List.of(
            new DocumentAcl("file:F1", DocumentAcl.Status.OK, Set.of(READER), NOW.plusSeconds(1))));
  }

  void missingAcl() {
    acls.delete(SpaceId.LEGACY, List.of("file:F1"));
  }

  JsonNode explain(String... arguments) {
    var manager = new DataSourceTransactionManager(dataSource);
    manager.setEnforceReadOnly(true);
    var tx = new TransactionTemplate(manager);
    tx.setReadOnly(true);
    return tx.execute(status -> run(arguments));
  }

  private JsonNode run(String... arguments) {
    var queries = new PostgresCollectionConfiguration().newAdapters(dataSource, registry).queries();
    var explainer =
        new AccessExplainer(
            queries,
            new PostgresIndexConfiguration().newIdentityStore(dataSource),
            new DomainPolicy(Set.of(), Map.of()));
    List<String> outputs = new ArrayList<>();
    var commands =
        new QueryCommands(
            queries,
            registry,
            new FreshnessReader(PostgresTestAdapters.unitMaintenance(dataSource), clock),
            Set.of(SpaceId.LEGACY),
            PostgresTestAdapters.spaceMeta(dataSource),
            QueryScenario.CONFIGURED_GENERATION,
            clock,
            new SourceQueryJson(QueryScenario.JSON),
            outputs::add,
            true,
            explainer);
    var args = new CommandArguments(new DefaultApplicationArguments(arguments));
    commands.source("access", args);
    return QueryScenario.JSON.readTree(outputs.getFirst());
  }
}
