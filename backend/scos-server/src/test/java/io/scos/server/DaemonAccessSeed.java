package io.scos.server;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.SourceInstances;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.Group;
import io.scos.spi.access.AccessDirectory.Identity;
import io.scos.spi.access.AccessDirectory.Link;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.TokenHash;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresAccessSeed;
import io.scos.store.postgres.PostgresAclStore;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /** 관리 journey는 문서·원천 관측만 심는다. 사용자·연결·토큰은 공개 관리 경로가 만든다. */
  void seedJourneyDocuments() {
    var sources = PostgresCollectionConfiguration.adapters(dataSource).sources();
    for (var source : List.of(SOURCE, new SourceInstanceId("notion-a"))) {
      String kind = source.equals(SOURCE) ? "slack" : "notion";
      sources.save(
          SourceInstances.of(source, kind, "{}", "env:UNUSED_SYNTHETIC", OBSERVED)
              .withEnabled(false, OBSERVED));
    }
    Instant now = Instant.now();
    Map<String, SourceIdentityRecord> identities = new HashMap<>();
    Map<String, GroupSnapshot> groups = new HashMap<>();
    for (String id : List.of(ALICE, BOB)) {
      String sourceUser = id.equals(ALICE) ? "U01" : "U02";
      identities.put(
          sourceUser,
          new SourceIdentityRecord(sourceUser, Optional.empty(), false, false, false, now));
      groups.put(id + "-team", new GroupSnapshot(id + "-team", Set.of(sourceUser), now));
    }
    indexes.newSourceAccessDirectory(dataSource).save(SOURCE, new Snapshot(identities, groups));
    document(ALICE_DOCUMENT, Set.of(group(ALICE)));
    document(BOB_DOCUMENT, Set.of(group(BOB)));
    document(SHARED_DOCUMENT, Set.of(group(ALICE), group(BOB)));
    document("no-acl-guide", Set.of());
    var notion = new SourceInstanceId("notion-a");
    document(
        "notion-guide",
        notion,
        DocumentAcl.Status.UNSUPPORTED,
        Set.of(new AccessKey(AccessKey.Kind.SCOPE, notion, "*")));
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
    document(id, SOURCE, DocumentAcl.Status.OK, keys);
  }

  private void document(
      String id, SourceInstanceId source, DocumentAcl.Status status, Set<AccessKey> keys) {
    double[] embedding = new double[DIMENSIONS];
    Arrays.fill(embedding, 0.1);
    var document =
        unit(id)
            .source(source.value())
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
          .put(SpaceId.LEGACY, List.of(new DocumentAcl(id, status, keys, OBSERVED)));
    }
  }
}
