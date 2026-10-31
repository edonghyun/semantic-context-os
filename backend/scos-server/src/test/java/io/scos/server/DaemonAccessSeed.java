package io.scos.server;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.boot.PostgresIndexConfiguration;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.Group;
import io.scos.spi.access.AccessDirectory.Identity;
import io.scos.spi.access.AccessDirectory.Link;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.TokenHash;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresAccessSeed;
import io.scos.store.postgres.PostgresAclStore;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 수집과 독립된 문서·신원·그룹을 실제 어댑터에 심어 HTTP 경계의 가시성을 고정한다. */
@Tag("db")
final class DaemonAccessSeed {
  static final String ALICE = "alice";
  static final String BOB = "bob";
  static final String DEACTIVATED = "deactivated";
  static final String INACTIVE = "inactive";
  static final String ALICE_TOKEN = "scos_alice_process_fixture";
  static final String BOB_TOKEN = "scos_bob_process_fixture";
  static final String DEACTIVATED_TOKEN = "scos_deactivated_process_fixture";
  static final String INACTIVE_TOKEN = "scos_inactive_process_fixture";
  static final String REVOKED_TOKEN = "scos_revoked_process_fixture";
  static final String WRONG_TOKEN = "scos_unknown_process_fixture";
  static final String ALICE_DOCUMENT = "alice-guide";
  static final String BOB_DOCUMENT = "bob-guide";
  static final String SHARED_DOCUMENT = "shared-guide";
  static final String QUERY = "정산";
  private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
  private static final Instant OBSERVED = Instant.parse("2026-10-28T00:00:00Z");
  private static final int DIMENSIONS = 768;
  private final DataSource dataSource;
  private final PostgresIndexConfiguration indexes = new PostgresIndexConfiguration();

  DaemonAccessSeed(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  void seed() {
    var directory = AccessDirectory.empty();
    for (String id : List.of(ALICE, BOB, DEACTIVATED, INACTIVE)) {
      directory = directory.withUser(new User(id, id + "@example.com", !id.equals(INACTIVE)));
    }
    for (String id : List.of(ALICE, BOB)) {
      directory =
          directory
              .withIdentity(new Identity(SOURCE, id, false, false))
              .withLink(new Link(SOURCE, id, id, "admin"))
              .withGroup(new Group(SOURCE, id + "-team", Set.of(id)));
    }
    new PostgresAccessSeed(dataSource).seed(directory);
    seedTokens();
    document(ALICE_DOCUMENT, Set.of(group(ALICE)));
    document(BOB_DOCUMENT, Set.of(group(BOB)));
    document(SHARED_DOCUMENT, Set.of(group(ALICE), group(BOB)));
    document("no-acl-guide", Set.of());
  }

  private void seedTokens() {
    var tokens = indexes.newUserTokens(dataSource, dataSource);
    tokens.store(TokenHash.of(ALICE_TOKEN), ALICE, "process", OBSERVED);
    tokens.store(TokenHash.of(BOB_TOKEN), BOB, "process", OBSERVED);
    tokens.store(TokenHash.of(DEACTIVATED_TOKEN), DEACTIVATED, "process", OBSERVED);
    tokens.store(TokenHash.of(INACTIVE_TOKEN), INACTIVE, "process", OBSERVED);
    tokens.store(TokenHash.of(REVOKED_TOKEN), ALICE, "process", OBSERVED);
    tokens.revoke(TokenHash.of(REVOKED_TOKEN), OBSERVED);
  }

  private AccessKey group(String user) {
    return new AccessKey(AccessKey.Kind.GROUP, SOURCE, user + "-team");
  }

  private void document(String id, Set<AccessKey> keys) {
    double[] embedding = new double[DIMENSIONS];
    Arrays.fill(embedding, 0.1);
    var document =
        unit(id)
            .source(SOURCE.value())
            .inDocument(id)
            .titled(QUERY + " 가이드")
            .content(QUERY + " " + id)
            .embedding(embedding)
            .build();
    indexes.newUnitMaintenance(dataSource).upsert(SpaceId.LEGACY, List.of(document));
    PostgresIndexConfiguration.newKeywordIndex(dataSource)
        .upsert(
            SpaceId.LEGACY,
            List.of(
                new IndexedText(
                    id, id, document.title(), document.content(), document.partition(), false)));
    if (!keys.isEmpty()) {
      new PostgresAclStore(JdbcClient.create(dataSource))
          .put(SpaceId.LEGACY, List.of(new DocumentAcl(id, DocumentAcl.Status.OK, keys, OBSERVED)));
    }
  }
}
