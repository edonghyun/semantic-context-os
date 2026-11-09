package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstances;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.googledrive.internal.DriveAccessCollection;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.AccessCost;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import io.scos.support.FakeHttpServer;
import io.scos.support.LogCapture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Timeout(900)
@Tag("perf")
@Tag("db")
@Tag("process")
@DisplayName("동일한 합성 Drive 5천 파일의 두 번째 접근 run 비용을 기록한다(T-c16)")
class AccessReleaseCostProbeTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private CostScenario scenario;

  @AfterEach
  void closeScenario() {
    if (scenario != null) scenario.close();
  }

  @Test
  @DisplayName("권한 재관측은 고수위만 갱신하고 같은 run의 실제 비용을 남긴다")
  void repeatedPermissionsKeepOnlyObservationHighWater() throws Exception {
    scenario = new CostScenario();
    // when
    var observation = scenario.measure();
    // then
    // 속도 임계는 환경별 결과로 기록하며 JUnit에서는 저장·계수 계약만 단언한다.
    assertThat(observation)
        .usingRecursiveComparison()
        .isEqualTo(new Observation(5000, 5000, 0, 0, false, 5000, true, true));
  }

  @Test
  @DisplayName("저장값과 로그가 함께 호출을 덜 세어도 실제 HTTP 수와 다르면 거부한다")
  void matchingStoredAndLoggedUndercountDoesNotPass() {
    var cost = new AccessCost(5000, OptionalLong.of(4999), 1250);
    var evidence = new CostEvidence(cost, cost, 5000);
    // when
    boolean matches = evidence.consistent();
    // then
    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("접근 전체 소요 시간은 공급자 루프 소요 시간을 포함한다")
  void collectionElapsedIncludesConnectorLoop() {
    var stored = new AccessCost(5000, OptionalLong.of(5000), 1250);
    var logged = new AccessCost(5000, OptionalLong.of(5000), 1200);
    var evidence = new CostEvidence(stored, logged, 5000);
    // when
    boolean matches = evidence.consistent();
    // then
    assertThat(matches).isTrue();
  }

  private record CostEvidence(AccessCost stored, AccessCost logged, long httpCalls) {
    boolean consistent() {
      return stored.targetCount() == logged.targetCount()
          && stored.callCount().equals(logged.callCount())
          && stored.elapsedMs() >= logged.elapsedMs()
          && stored.callCount().isPresent()
          && stored.callCount().orElseThrow() == httpCalls;
    }
  }

  private record Observation(
      int targets,
      long highWaterUpdates,
      long semanticUpdates,
      long documentAclUpdates,
      boolean projectionRequested,
      long calls,
      boolean costMatchesEvidence,
      boolean costRunMatches) {}

  /** 본문은 미리 적재하고 실제 Drive 권한 수집과 PostgreSQL 커밋만 측정한다. */
  private static final class CostScenario implements AutoCloseable {
    private static final int FILES = 5_000;
    private static final SourceInstanceId SOURCE = new SourceInstanceId("probe-drive");
    private final Clock clock = Clock.systemUTC();
    private final ThreadPoolTaskScheduler heartbeats = new ThreadPoolTaskScheduler();
    private final FakeHttpServer server = new FakeHttpServer();
    private final HikariDataSource database = pool();
    private final JdbcClient jdbc = JdbcClient.create(database);
    private final SourceAdapter adapter =
        new PermissionsOnlySource(
            GoogleDriveAssembly.adapter(
                server.baseUrl(), GoogleDriveAssembly.CREDENTIAL_JSON, clock));
    private final PostgresCollectionConfiguration.Adapters stores =
        new PostgresCollectionConfiguration()
            .newAdapters(database, SourceAdapters.of(List.of(adapter)));
    private final CollectionRuntime runtime =
        PostgresCollectionRuntime.over(database, clock)
            .adapter(adapter)
            .heartbeats(heartbeats)
            .collectAccess(true)
            .build();

    CostScenario() throws Exception {
      try {
        heartbeats.initialize();
        removeWriteCounters();
        prepareFiles();
      } catch (Exception | Error failure) {
        try {
          close();
        } catch (RuntimeException | Error cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }

    private void prepareFiles() {
      PostgresSupport.truncateAll();
      server.on(
          "POST",
          "/_oauth/token",
          request ->
              FakeHttpServer.ok("{\"access_token\":\"synthetic-access\",\"expires_in\":3600}"));
      server.limitRequests(10_002, FakeHttpServer.status(400, "{}"));
      stores
          .sources()
          .save(
              SourceInstances.of(
                  SOURCE,
                  "google-drive",
                  "{\"accountEmail\":\"reader@example.com\",\"permissionVisibility\":\"VERIFIED_FULL\"}",
                  GoogleDriveAssembly.CREDENTIAL_REF,
                  clock.instant()));
      var tx = new TransactionTemplate(new DataSourceTransactionManager(database));
      tx.executeWithoutResult(
          status -> {
            for (int index = 0; index < FILES; index++) {
              String id = "F" + index;
              stores
                  .rawItems()
                  .apply(SOURCE, RawItems.item("file:" + id).resourceType("gdrive.file").upsert());
              server.on(
                  "GET",
                  "/_drive/drive/v3/files/" + id + "/permissions",
                  request ->
                      new FakeHttpServer.Response(
                          200,
                          "{\"permissions\":[{\"id\":\"reader\",\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"reader@example.com\"}]}",
                          Map.of("Connection", "close")));
            }
          });
    }

    private HikariDataSource pool() {
      var config = new HikariConfig();
      config.setDataSource(PostgresSupport.dataSource());
      config.setMaximumPoolSize(6);
      config.setMinimumIdle(0);
      config.setPoolName("access-release-synthetic");
      return new HikariDataSource(config);
    }

    Observation measure() throws Exception {
      collectComplete();
      prepareProjectedDocuments();
      installWriteCounters();
      var before = stores.outbox().request(SOURCE).orElseThrow();
      if (!stores.outbox().complete(SOURCE, before.version()))
        throw new IllegalStateException("투영 요청 준비 실패");
      int callsBefore = permissionCalls();
      Observation observation;
      try (var logs = LogCapture.of(DriveAccessCollection.class)) {
        collectComplete();
        var run = stores.runs().latest(SOURCE).orElseThrow();
        var recorded = run.accessCost().orElseThrow();
        var cost = recorded.cost();
        var queried = stores.queries().accessStatus(SOURCE).orElseThrow().cost().orElseThrow();
        var logged = logs.events("collection.drive_access.finished").getFirst().path("detail");
        var loggedCost =
            new AccessCost(
                logged.path("targetCount").asLong(),
                OptionalLong.of(logged.path("permissionCalls").asLong()),
                logged.path("elapsedMs").asLong());
        boolean matches =
            new CostEvidence(cost, loggedCost, permissionCalls() - callsBefore).consistent();
        observation =
            new Observation(
                (int) cost.targetCount(),
                count("high_water"),
                count("semantic"),
                count("document_acl"),
                stores.outbox().request(SOURCE).isPresent(),
                permissionCalls() - callsBefore,
                matches,
                queried.runId().equals(run.id()) && queried.cost().equals(cost));
        writeReport(observation, run.id().value(), cost, loggedCost, recorded.recordedAt());
      }
      return observation;
    }

    private void collectComplete() {
      runtime.collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
      var run = stores.runs().latest(SOURCE).orElseThrow();
      if (run.status() != RunStatus.SUCCEEDED || run.accessResult() != AccessResult.COMPLETE)
        throw new IllegalStateException("given 준비 실패: 합성 권한 run이 COMPLETE가 아니다");
    }

    private void prepareProjectedDocuments() {
      var index = new PostgresIndexConfiguration();
      var ids = IntStream.range(0, FILES).mapToObj(i -> "file:F" + i).toList();
      var observed = stores.rawAcls().get(SOURCE, ids);
      var decisions =
          observed.entrySet().stream()
              .map(
                  entry ->
                      new DocumentAcl(
                          entry.getKey(),
                          entry.getValue().status(),
                          entry.getValue().keys(),
                          clock.instant()))
              .toList();
      var tx = new TransactionTemplate(new DataSourceTransactionManager(database));
      tx.executeWithoutResult(
          status -> {
            for (String id : ids)
              index.newDocumentOwnership(database).claim(SpaceId.LEGACY, SOURCE.value(), id);
            index.newAclStore(database).put(SpaceId.LEGACY, decisions);
          });
    }

    private void installWriteCounters() {
      removeWriteCounters();
      jdbc.sql("CREATE TABLE access_release_writes (kind text PRIMARY KEY, writes bigint NOT NULL)")
          .update();
      jdbc.sql(
              "INSERT INTO access_release_writes VALUES ('high_water',0),('semantic',0),('document_acl',0)")
          .update();
      jdbc.sql(
              """
          CREATE FUNCTION count_access_release_write() RETURNS trigger LANGUAGE plpgsql AS $$
          DECLARE write_kind text;
          BEGIN
            IF TG_TABLE_NAME = 'document_acl' THEN write_kind := 'document_acl';
            ELSIF (NEW.status,NEW.keys,NEW.fingerprint,NEW.reason) IS DISTINCT FROM
                  (OLD.status,OLD.keys,OLD.fingerprint,OLD.reason) THEN write_kind := 'semantic';
            ELSIF NEW.observed_at > OLD.observed_at THEN write_kind := 'high_water';
            ELSE RETURN NULL;
            END IF;
            UPDATE access_release_writes SET writes = writes + 1 WHERE access_release_writes.kind = write_kind;
            RETURN NULL;
          END $$
          """)
          .update();
      for (String table : List.of("raw_acl", "document_acl"))
        jdbc.sql(
                "CREATE TRIGGER count_access_release_write AFTER UPDATE ON "
                    + table
                    + " FOR EACH ROW EXECUTE FUNCTION count_access_release_write()")
            .update();
    }

    private long count(String kind) {
      return jdbc.sql("SELECT writes FROM access_release_writes WHERE kind = :kind")
          .param("kind", kind)
          .query(Long.class)
          .single();
    }

    private int permissionCalls() {
      return (int)
          server.received().stream()
              .filter(request -> request.path().endsWith("/permissions"))
              .count();
    }

    private void writeReport(
        Observation observation, String run, AccessCost cost, AccessCost connectorCost, Instant at)
        throws Exception {
      var report = new LinkedHashMap<String, Object>();
      report.put("scope", "synthetic-tooling-only");
      report.put("approvedMeasurement", false);
      report.put("environment", environment(at));
      report.put("runId", run);
      report.put("recordedAt", at.toString());
      report.put("cost", cost);
      report.put("connectorLoopCost", connectorCost);
      report.put("observation", observation);
      report.put("measurements", Map.of("P-M6", measurementRows(observation)));
      var output = Path.of("build/reports/access-release/drive-cost.json");
      Files.createDirectories(output.getParent());
      Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private Map<String, Object> environment(Instant at) throws Exception {
      var git = new ProcessBuilder("git", "rev-parse", "HEAD").start();
      String head = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      if (git.waitFor() != 0 || !head.matches("[0-9a-f]{40}")) throw new IOException("HEAD 조회 실패");
      var jar = Path.of("build/libs/scos-backend.jar");
      String digest =
          Files.isRegularFile(jar)
              ? HexFormat.of()
                  .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))
              : "미측정";
      return Map.ofEntries(
          Map.entry("head", head),
          Map.entry("jarSha256", digest),
          Map.entry(
              "schema",
              jdbc.sql(
                      "SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL")
                  .query(Integer.class)
                  .single()),
          Map.entry("java", System.getProperty("java.version")),
          Map.entry("os", System.getProperty("os.name")),
          Map.entry("files", FILES),
          Map.entry("writePoolMax", 6),
          Map.entry("fakeTransport", "connection-close"),
          Map.entry("measuredAt", at.toString()));
    }

    private record Comparison(long actual, long limit, String bound, String unit, String status) {}

    private List<Comparison> measurementRows(Observation observation) {
      return List.of(
          comparison(observation.semanticUpdates()),
          comparison(observation.documentAclUpdates()),
          comparison(observation.projectionRequested() ? 1 : 0),
          new Comparison(
              observation.costMatchesEvidence() && observation.costRunMatches() ? 1 : 0,
              1,
              "EQ",
              "verified",
              observation.costMatchesEvidence() && observation.costRunMatches() ? "PASS" : "FAIL"));
    }

    private Comparison comparison(long actual) {
      return new Comparison(actual, 0, "EQ", "count", actual == 0 ? "PASS" : "FAIL");
    }

    private void removeWriteCounters() {
      jdbc.sql("DROP TRIGGER IF EXISTS count_access_release_write ON raw_acl").update();
      jdbc.sql("DROP TRIGGER IF EXISTS count_access_release_write ON document_acl").update();
      jdbc.sql("DROP FUNCTION IF EXISTS count_access_release_write()").update();
      jdbc.sql("DROP TABLE IF EXISTS access_release_writes").update();
    }

    @Override
    public void close() {
      try {
        removeWriteCounters();
      } finally {
        heartbeats.shutdown();
        database.close();
        server.close();
      }
    }
  }

  private record PermissionsOnlySource(SourceAdapter drive) implements SourceAdapter {
    @Override
    public String kind() {
      return drive.kind();
    }

    @Override
    public void validateConfig(SourceConfig config) {
      drive.validateConfig(config);
    }

    @Override
    public CollectionCoverage coverage(
        SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
      return CollectionCoverage.INCREMENTAL;
    }

    @Override
    public void collect(CollectionInput input, PageSink sink) {}

    @Override
    public AccessPolicy accessPolicy() {
      return drive.accessPolicy();
    }
  }
}
