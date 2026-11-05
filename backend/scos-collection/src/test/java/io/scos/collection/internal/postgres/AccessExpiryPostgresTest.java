package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.AccessCollection;
import io.scos.collection.internal.AccessExpiry;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.slack.SlackAssembly;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore.Range;
import io.scos.store.postgres.PostgresTestAdapters;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("db")
@DisplayName("주기 만료의 PostgreSQL 쓰기는 비활성 소스의 읽기 권한도 회수한다")
class AccessExpiryPostgresTest {
  ExpiryScenario scenario;

  @BeforeEach
  void setUp() {
    PostgresSupport.truncateAll();
    scenario = new ExpiryScenario();
  }

  @Test
  @DisplayName("비활성 소스는 수집·투영 실행 없이 실제 읽기에서 숨겨진다")
  void disabledSourceIsHiddenWithoutProjection() {
    scenario.givenDrive();
    scenario.sweep();
    // then
    assertThat(scenario.read()).isEmpty();
    assertThat(scenario.document().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("닫힌 뒤 새 관측 없는 만료 후보는 0행이다")
  void repeatedSweepHasNoCandidates() {
    // given
    scenario.givenDrive();
    scenario.sweep();
    // when
    var candidates = scenario.expiredCandidates();
    // then
    assertThat(candidates.items()).isEmpty();
  }

  @Test
  @DisplayName("닫힌 뒤 새 관측 없는 sweep의 소스·원본·소유·문서 행 잠금은 0이다")
  void repeatedSweepLocksNoClosedRows() {
    scenario.givenDrive();
    scenario.sweep();
    scenario.givenSecondClosedDocument();
    var before = scenario.rowVersions();
    scenario.sweep();
    // then
    assertThat(scenario.lockedRowsSince(before)).isZero();
  }

  @Test
  @DisplayName("닫힌 뒤 새 관측 없는 sweep의 원본·소유·문서 쓰기는 0이다")
  void repeatedSweepWritesNoClosedRows() {
    scenario.givenDrive();
    scenario.sweep();
    scenario.givenSecondClosedDocument();
    var before = scenario.rowVersions();
    scenario.sweep();
    // then
    assertThat(scenario.writtenRowsSince(before)).isZero();
  }

  @Test
  @DisplayName("다른 소스 소유 OK 문서를 둔 반복 sweep의 후보는 0행이다")
  void repeatedForeignOwnerSweepHasNoCandidates() {
    scenario.givenForeignOwnedDrive();
    scenario.sweep();
    // then
    assertThat(scenario.expiredCandidates().items()).isEmpty();
  }

  @Test
  @DisplayName("다른 소스 소유 OK 문서를 둔 반복 sweep은 소스·소유 행도 잠그지 않는다")
  void repeatedForeignOwnerSweepLocksNoRows() {
    scenario.givenForeignOwnedDrive();
    scenario.sweep();
    var before = scenario.rowVersions();
    scenario.sweep();
    // then
    assertThat(scenario.lockedRowsSince(before)).isZero();
  }

  @Test
  @DisplayName("다른 소스 소유 OK 문서를 둔 반복 sweep은 원본도 다시 쓰지 않는다")
  void repeatedForeignOwnerSweepWritesNoRows() {
    scenario.givenForeignOwnedDrive();
    scenario.sweep();
    var before = scenario.rowVersions();
    scenario.sweep();
    // then
    assertThat(scenario.writtenRowsSince(before)).isZero();
  }

  @Test
  @DisplayName("같은 만료 완료 표식을 다시 기록해도 원본을 쓰지 않는다")
  void identicalCompletionWritesNoRows() {
    scenario.givenDrive();
    scenario.sweep();
    var before = scenario.rowVersions();
    scenario.completeExpiryAgain();
    // then
    assertThat(scenario.writtenRowsSince(before)).isZero();
  }

  @Test
  @DisplayName("닫힌 뒤 새 원본 관측은 다시 만료 대상이 된다")
  void newObservationIsExpiredAgain() {
    scenario.givenDrive();
    scenario.sweep();
    scenario.observeAgain();
    scenario.sweep();
    // then
    assertThat(
            scenario
                .adapters
                .rawAcls()
                .get(ExpiryScenario.DRIVE, List.of("file:F1"))
                .get("file:F1")
                .reason())
        .isEqualTo(AccessFailureReason.EXPIRED);
  }

  @Test
  @DisplayName("이미 만료된 원본 뒤 지연된 OK 문서도 다시 닫는다")
  void lateVisibleDocumentIsClosedAgain() {
    scenario.givenDrive();
    scenario.sweep();
    scenario.visibleDocumentAgain();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("문서 결정 시각은 전진하고 원천 관측 시각은 보존한다")
  void separateSourceAgeAndDecisionTime() {
    scenario.givenDrive();
    scenario.sweep();
    // then
    assertThat(scenario.document().observedAt()).isEqualTo(ExpiryScenario.NOW);
    assertThat(
            scenario
                .adapters
                .rawAcls()
                .get(ExpiryScenario.DRIVE, List.of("file:F1"))
                .get("file:F1")
                .observedAt())
        .isEqualTo(ExpiryScenario.T0);
  }

  @Test
  @DisplayName("비활성 Slack의 오래된 그룹과 신원은 권한을 넓히지 않는다")
  void disabledSlackDirectoryIsRestricted() {
    scenario.givenDirectory();
    scenario.sweep();
    // then
    assertThat(scenario.directory.current(ExpiryScenario.SLACK).groups().get("C1").members())
        .isEmpty();
    assertThat(scenario.directory.current(ExpiryScenario.SLACK).identities().get("U1").deleted())
        .isTrue();
  }

  static final class ExpiryScenario {
    static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
    static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    static final SourceInstanceId DRIVE = new SourceInstanceId("drive-a");
    static final SourceInstanceId SLACK = new SourceInstanceId("slack-a");
    static final Set<AccessKey> KEYS = Set.of(AccessKey.parse("user:drive-a:alice@example.com"));
    final MutableClock clock = new MutableClock(NOW);
    final PostgresCollectionConfiguration.Adapters adapters =
        PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
    final PostgresIndexConfiguration index = new PostgresIndexConfiguration();
    final AclStore acls = index.newAclStore(PostgresSupport.dataSource());
    final SourceAccessDirectory directory =
        index.newSourceAccessDirectory(PostgresSupport.dataSource());
    final SourceAdapters declarations =
        SourceAdapters.of(
            List.of(
                GoogleDriveAssembly.adapter(
                    "http://127.0.0.1:1", GoogleDriveAssembly.CREDENTIAL_JSON, clock),
                SlackAssembly.adapter("http://127.0.0.1:1", "synthetic", clock)));

    void givenDrive() {
      givenDrive(DRIVE);
    }

    private void givenDrive(SourceInstanceId owner) {
      adapters
          .sources()
          .save(
              SourceInstances.disabled(
                  SourceInstances.of(DRIVE, "google-drive", "{}", "env:FAKE", T0)));
      adapters
          .rawItems()
          .apply(DRIVE, RawItems.item("file:F1").resourceType("gdrive.file").upsert());
      adapters.rawAcls().observe(DRIVE, "file:F1", RawAcl.observed(Status.OK, KEYS, T0), T0);
      PostgresTestAdapters.unitMaintenance(PostgresSupport.dataSource())
          .upsert(
              SpaceId.LEGACY,
              List.of(ContextUnits.unit("u1").source(owner.value()).inDocument("file:F1").build()));
      acls.put(SpaceId.LEGACY, List.of(new DocumentAcl("file:F1", Status.OK, KEYS, T0)));
      if (read().isEmpty()) throw new IllegalStateException("given 준비 실패: 기존 문서는 읽을 수 있어야 한다");
    }

    void givenForeignOwnedDrive() {
      givenDrive(new SourceInstanceId("drive-b"));
    }

    void completeExpiryAgain() {
      adapters
          .rawItems()
          .completeAccessExpiry(DRIVE, "file:F1", T0, SpaceId.LEGACY, Optional.of("file:F1"));
    }

    record RowVersion(String relation, String key, String locked, String written) {}

    List<RowVersion> rowVersions() {
      return JdbcClient.create(PostgresSupport.dataSource())
          .sql(
              """
          SELECT 'raw' AS relation, jsonb_build_array(source_instance_id, external_id)::text AS key,
                 xmax::text AS locked, ctid::text AS written FROM raw_acl
          UNION ALL SELECT 'raw-item', jsonb_build_array(source_instance_id, external_id)::text, xmax::text, ctid::text FROM raw_items
          UNION ALL SELECT 'owner', document_id, xmax::text, ctid::text FROM document_spaces
          UNION ALL SELECT 'document', jsonb_build_array(space_id, document_id)::text, xmax::text, ctid::text FROM document_acl
          UNION ALL SELECT 'source', id, xmax::text, ctid::text FROM source_instances
          ORDER BY relation, key
          """)
          .query(
              (rs, row) ->
                  new RowVersion(
                      rs.getString("relation"),
                      rs.getString("key"),
                      rs.getString("locked"),
                      rs.getString("written")))
          .list();
    }

    long lockedRowsSince(List<RowVersion> before) {
      var after = rowVersions();
      return before.stream()
          .filter(
              row ->
                  after.stream()
                      .anyMatch(
                          next ->
                              next.relation().equals(row.relation())
                                  && next.key().equals(row.key())
                                  && !next.locked().equals(row.locked())))
          .count();
    }

    long writtenRowsSince(List<RowVersion> before) {
      var after = rowVersions();
      return before.stream()
          .filter(
              row ->
                  after.stream()
                      .anyMatch(
                          next ->
                              next.relation().equals(row.relation())
                                  && next.key().equals(row.key())
                                  && !next.written().equals(row.written())))
          .count();
    }

    void givenSecondClosedDocument() {
      adapters
          .rawItems()
          .apply(DRIVE, RawItems.item("file:F2").resourceType("gdrive.file").upsert());
      adapters.rawAcls().observe(DRIVE, "file:F2", RawAcl.observed(Status.OK, KEYS, T0), T0);
      PostgresTestAdapters.unitMaintenance(PostgresSupport.dataSource())
          .upsert(
              SpaceId.LEGACY,
              List.of(ContextUnits.unit("u2").source(DRIVE.value()).inDocument("file:F2").build()));
      acls.put(SpaceId.LEGACY, List.of(new DocumentAcl("file:F2", Status.OK, KEYS, T0)));
      sweep();
    }

    RawItemStore.Page expiredCandidates() {
      return adapters
          .rawItems()
          .accessCandidates(DRIVE, Set.of("gdrive.file"), Optional.of(T0), 500, Optional.empty());
    }

    void observeAgain() {
      adapters
          .rawAcls()
          .observe(DRIVE, "file:F1", RawAcl.observed(Status.OK, KEYS, T0.plusSeconds(1)), NOW);
      clock.advance(Duration.ofSeconds(1));
    }

    void visibleDocumentAgain() {
      acls.put(
          SpaceId.LEGACY, List.of(new DocumentAcl("file:F1", Status.OK, KEYS, NOW.plusSeconds(1))));
      clock.advance(Duration.ofSeconds(1));
    }

    void givenDirectory() {
      adapters
          .sources()
          .save(SourceInstances.disabled(SourceInstances.of(SLACK, "slack", "{}", "env:FAKE", T0)));
      directory.save(
          SLACK,
          new SourceAccessDirectory.Snapshot(
              Map.of(
                  "U1",
                  new SourceIdentityRecord(
                      "U1", Optional.of("alice@example.com"), true, false, false, T0)),
              Map.of("C1", new GroupSnapshot("C1", Set.of("U1"), T0))));
    }

    void sweep() {
      sweep(directory);
    }

    void sweep(SourceAccessDirectory observedDirectory) {
      var tx =
          new TransactionTemplate(new DataSourceTransactionManager(PostgresSupport.dataSource()));
      new AccessExpiry(
              adapters.stores(tx),
              declarations,
              new ProjectionSpaces(Map.of(), SpaceId.LEGACY, new ObjectMapper()),
              acls,
              index.newDocumentOwnership(PostgresSupport.dataSource()),
              observedDirectory,
              clock,
              AccessCollection.Options.DEFAULTS,
              true)
          .sweep(CancellationToken.none());
    }

    DocumentAcl document() {
      return acls.get(SpaceId.LEGACY, List.of("file:F1")).get("file:F1");
    }

    List<ContextUnit> read() {
      return PostgresTestAdapters.unitStore(PostgresSupport.dataSource())
          .listByDocument(
              SpaceId.LEGACY,
              new AccessScope.Restricted(new Principal("alice"), KEYS),
              "file:F1",
              new Range(0, 10));
    }
  }
}
