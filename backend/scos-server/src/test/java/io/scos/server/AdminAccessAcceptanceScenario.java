package io.scos.server;

import io.scos.boot.AdminConfiguration;
import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.AccessCollection;
import io.scos.collection.internal.AccessExpiry;
import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.AclCommit;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunOutcome;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSourceQueries;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.LogCapture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 합성 측정 결과만 분류한다. 운영 개방 승인과는 별개다. */
@Tag("db")
final class AdminAccessAcceptanceScenario {
  enum Bound {
    LT,
    LTE,
    EQ
  }

  record Measurement(String metric, double actual, double limit, Bound bound, String unit) {}

  record Assessment(boolean openEligible, Set<String> failedOrMissingIds) {}

  private static final List<String> REQUIRED =
      List.of("P-M0", "P-M1", "P-M2", "P-M3", "P-M4", "P-M5", "P-M6", "P-M7", "O-M1");

  Assessment assess(Map<String, List<Measurement>> measurements) {
    return assessment(evaluate(measurements));
  }

  private Assessment assessment(Map<String, List<Map<String, Object>>> rows) {
    var failed = new LinkedHashSet<String>();
    for (String id : REQUIRED) {
      if (rows.get(id).stream().anyMatch(row -> !row.get("status").equals("PASS"))) failed.add(id);
    }
    return new Assessment(failed.isEmpty(), Set.copyOf(failed));
  }

  void writeReport(
      Path output, Map<String, Object> environment, Map<String, List<Measurement>> measurements)
      throws IOException {
    var rows = evaluate(measurements);
    var result = assessment(rows);
    var report =
        Map.of(
            "environment",
            environment,
            "scope",
            "synthetic-tooling-only",
            "approvedMeasurement",
            false,
            "openEligible",
            result.openEligible(),
            "failedOrMissingIds",
            result.failedOrMissingIds(),
            "measurements",
            rows);
    Files.createDirectories(output.getParent());
    Files.writeString(
        output, QueryScenario.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
  }

  private Map<String, List<Map<String, Object>>> evaluate(
      Map<String, List<Measurement>> measurements) {
    var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (String id : REQUIRED) {
      var criteria = CRITERIA.get(id);
      var supplied = measurements.getOrDefault(id, List.of());
      boolean knownMetrics =
          supplied.stream()
              .allMatch(
                  value ->
                      criteria.stream()
                          .anyMatch(criterion -> criterion.name().equals(value.metric())));
      rows.put(
          id,
          criteria.stream().map(criterion -> criterion.evaluate(supplied, knownMetrics)).toList());
    }
    return rows;
  }

  private record Criterion(String name, double limit, Bound bound, String unit) {
    Map<String, Object> evaluate(List<Measurement> supplied, boolean knownMetrics) {
      var matches = supplied.stream().filter(value -> name.equals(value.metric())).toList();
      var value = matches.size() == 1 ? matches.getFirst() : null;
      boolean valid =
          knownMetrics
              && value != null
              && value.limit() == limit
              && value.bound() == bound
              && unit.equals(value.unit());
      String status =
          value == null || !Double.isFinite(value.actual())
              ? "미측정"
              : valid && passes(value.actual()) ? "PASS" : "FAIL";
      var row = new LinkedHashMap<String, Object>();
      row.put("metric", name);
      row.put("actual", value != null && Double.isFinite(value.actual()) ? value.actual() : null);
      row.put("limit", limit);
      row.put("bound", bound.name());
      row.put("unit", unit);
      row.put("status", status);
      return row;
    }

    private boolean passes(double actual) {
      return switch (bound) {
        case LT -> actual < limit;
        case LTE -> actual <= limit;
        case EQ -> actual == limit;
      };
    }
  }

  // metric 이름이 승인 측정 입력의 식별자이며 목록 순서와 무관하다. 비수치 증거는 확인되었을 때만 1을 넣는다.
  private static final Map<String, List<Criterion>> CRITERIA =
      Map.of(
          "P-M0",
              List.of(
                  new Criterion("v28", 1, Bound.LT, "s"),
                  new Criterion("exclusiveLock", 1, Bound.LT, "s"),
                  new Criterion("v34", 1, Bound.LT, "s"),
                  new Criterion("v35", 1, Bound.LT, "s"),
                  new Criterion("previousJarAndR0Evidence", 1, Bound.EQ, "verified")),
          "P-M1",
              List.of(
                  new Criterion("documentSpacesUpdates", 0, Bound.EQ, "count"),
                  new Criterion("commitRatio", 1.1, Bound.LTE, "ratio"),
                  new Criterion("wallRatio", 1.2, Bound.LTE, "ratio")),
          "P-M2",
              List.of(
                  new Criterion("total", 600, Bound.LT, "s"),
                  new Criterion("batchTransaction", 2, Bound.LT, "s"),
                  new Criterion("postGcHeap", 1, Bound.LT, "GB"),
                  new Criterion("poolPending", 0, Bound.EQ, "count")),
          "P-M3",
              List.of(
                  new Criterion("sweep", 200, Bound.LT, "ms"),
                  new Criterion("slackCarrierExpired", 0, Bound.EQ, "count"),
                  new Criterion("tickEvidence", 1, Bound.EQ, "verified")),
          "P-M4",
              List.of(
                  new Criterion("restrictedP95Ratio", 1.3, Bound.LTE, "ratio"),
                  new Criterion("repeatedGinScans", 0, Bound.EQ, "count"),
                  new Criterion("qualityAndSubsetEvidence", 1, Bound.EQ, "verified")),
          "P-M5",
              List.of(
                  new Criterion("status", 1, Bound.LT, "s/source"),
                  new Criterion("adminSourceAccess", 1, Bound.LT, "s/source"),
                  new Criterion("costRunMatches", 1, Bound.EQ, "verified"),
                  new Criterion("buffersEvidence", 1, Bound.EQ, "verified")),
          "P-M6",
              List.of(
                  new Criterion("rawSemanticUpdates", 0, Bound.EQ, "count"),
                  new Criterion("documentAclUpdates", 0, Bound.EQ, "count"),
                  new Criterion("projectionRequests", 0, Bound.EQ, "count"),
                  new Criterion("costMatchesLog", 1, Bound.EQ, "verified")),
          "P-M7",
              List.of(
                  new Criterion("writeActive", 5, Bound.LTE, "count"),
                  new Criterion("pending", 0, Bound.EQ, "count"),
                  new Criterion("acquisitionFailures5s", 0, Bound.EQ, "count"),
                  new Criterion("separateReadPoolEvidence", 1, Bound.EQ, "verified")),
          "O-M1",
              List.of(
                  new Criterion("distinctCauses", 3, Bound.EQ, "count"),
                  new Criterion("unobservedWarning", 1, Bound.EQ, "verified"),
                  new Criterion("safeLogEvidence", 1, Bound.EQ, "verified")));

  record Evidence(String summary, boolean visible) {}

  /** 합성 DB에 저장된 원인을 실제 관리 포트로 읽는다. 토큰은 발급하지 않는다. */
  @Tag("db")
  @Tag("process")
  static final class Diagnostics {
    private static final Instant T0 = Instant.parse("2026-11-08T00:00:00Z");
    private static final Duration MAX_AGE = Duration.ofMinutes(1);
    private static final Duration HALF_AGE = Duration.ofSeconds(30);
    private static final SourceInstanceId SOURCE = new SourceInstanceId("probe-drive");
    private static final String DOCUMENT = "file:F1";
    private static final Principal ADMIN = new Principal("admin");
    private static final DomainPolicy DOMAINS = new DomainPolicy(Set.of(), Map.of());
    private static final AccessKey KEY = AccessKey.parse("user:probe-drive:reader@example.com");
    private final MutableClock clock = new MutableClock(T0.plusSeconds(1));
    private final DataSource database = PostgresSupport.dataSource();
    private final JdbcClient jdbc = JdbcClient.create(database);
    private final SourceAdapter adapter = new MissingTargetSource();
    private final SourceAdapters registry = SourceAdapters.of(List.of(adapter));
    private final PostgresCollectionConfiguration.Adapters stores =
        new PostgresCollectionConfiguration().newAdapters(database, registry);
    private final PostgresIndexConfiguration index = new PostgresIndexConfiguration();
    private final AclStore acls = index.newAclStore(database);
    private final AdminStore administration = index.newAdminStore(database);
    private final ProjectionSpaces spaces =
        new ProjectionSpaces(Map.of(), SpaceId.LEGACY, QueryScenario.JSON);
    private final SourceQueries queries =
        new PostgresSourceQueries(
            jdbc,
            stores.guards(),
            stores.rawAcls(),
            spaces,
            clock,
            new AccessCollection.Options(MAX_AGE),
            registry,
            acls);
    private final AdminPort port =
        AdminConfiguration.newAdminPort(
            administration,
            clock,
            DOMAINS,
            new AdminAccessQueries(
                queries,
                new AccessExplainer(queries, index.newIdentityStore(database), DOMAINS),
                administration,
                clock));
    private double sweepMs;
    private boolean safeLogs = true;
    private boolean unobserved;

    Diagnostics() {
      PostgresSupport.truncateAll();
      stores.sources().save(SourceInstances.of(SOURCE, "google-drive", "{}", "env:SYNTHETIC", T0));
      administration.transact(
          session -> {
            session.insertUser(new UserAccount("admin", "admin@example.com", true, Role.ADMIN, T0));
            session.insertUser(
                new UserAccount("reader", "reader@example.com", true, Role.MEMBER, T0));
            return true;
          });
      observeReader();
      port.linkIdentity(ADMIN, "reader", SOURCE, "reader@example.com");
      index.newDocumentOwnership(database).claim(SpaceId.LEGACY, SOURCE.value(), DOCUMENT);
      PostgresTestAdapters.unitMaintenance(database)
          .upsert(
              SpaceId.LEGACY,
              List.of(
                  ContextUnits.unit("probe-unit")
                      .source(SOURCE.value())
                      .inDocument(DOCUMENT)
                      .build()));
      stores.rawItems().apply(SOURCE, RawItems.item(DOCUMENT).resourceType("gdrive.file").upsert());
      freshDocument();
    }

    List<Evidence> diagnose() {
      var missing = incomplete();
      var expired = expired();
      var unlinked = unlinked();
      port.linkIdentity(ADMIN, "reader", SOURCE, "not-observed@example.com");
      var user =
          port.explainAccess(ADMIN, DOCUMENT, Optional.of("reader"))
              .orElseThrow()
              .user()
              .orElseThrow();
      unobserved =
          user.keys().isEmpty() && user.links().getFirst().warnings().equals(Set.of("unobserved"));
      return List.of(missing, expired, unlinked);
    }

    private Evidence incomplete() {
      clock.advance(Duration.ofSeconds(1));
      try (var logs = LogCapture.of(AclCommit.class)) {
        PostgresCollectionRuntime.over(database, clock)
            .adapter(adapter)
            .collectAccess(true)
            .build()
            .collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
        // 투영 완료 시의 저장 상태를 준비한다. 투영 자체의 회귀는 c14 journey가 소유한다.
        acls.put(
            SpaceId.LEGACY,
            List.of(
                new DocumentAcl(
                    DOCUMENT,
                    DocumentAcl.Status.FAILED,
                    Set.of(),
                    clock.instant(),
                    AccessFailureReason.MISSING_TARGET)));
        return evidence(logs);
      }
    }

    private Evidence expired() {
      clock.advance(Duration.ofSeconds(1));
      freshDocument();
      var run = new RunId("probe-complete");
      stores.runs().start(run, SOURCE, clock.instant());
      stores
          .runs()
          .finish(
              run,
              RunOutcome.succeeded(0),
              CollectionCoverage.INCREMENTAL,
              clock.instant(),
              0,
              AccessResult.COMPLETE,
              Optional.of(clock.instant()));
      stores.sources().save(SourceInstances.disabled(stores.sources().get(SOURCE).orElseThrow()));
      clock.advance(HALF_AGE);
      observeReader();
      clock.advance(HALF_AGE);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(database));
      var expiry =
          new AccessExpiry(
              stores.stores(tx),
              registry,
              spaces,
              acls,
              index.newDocumentOwnership(database),
              index.newSourceAccessDirectory(database),
              clock,
              new AccessCollection.Options(MAX_AGE),
              true);
      try (var logs = LogCapture.of(AccessExpiry.class)) {
        long started = System.nanoTime();
        expiry.sweep(CancellationToken.none());
        sweepMs = (System.nanoTime() - started) / 1_000_000.0;
        return evidence(logs);
      }
    }

    private Evidence unlinked() {
      clock.advance(Duration.ofSeconds(1));
      freshDocument();
      port.unlinkIdentity(ADMIN, SOURCE, "reader@example.com");
      try (var logs = LogCapture.of(AccessExpiry.class)) {
        return evidence(logs);
      }
    }

    private void freshDocument() {
      stores
          .rawAcls()
          .observe(
              SOURCE,
              DOCUMENT,
              RawAcl.observed(DocumentAcl.Status.OK, Set.of(KEY), clock.instant()),
              clock.instant());
      acls.put(
          SpaceId.LEGACY,
          List.of(new DocumentAcl(DOCUMENT, DocumentAcl.Status.OK, Set.of(KEY), clock.instant())));
    }

    private void observeReader() {
      index
          .newSourceAccessDirectory(database)
          .save(
              SOURCE,
              new SourceAccessDirectory.Snapshot(
                  Map.of(
                      "reader@example.com",
                      new SourceIdentityRecord(
                          "reader@example.com",
                          Optional.of("reader@example.com"),
                          true,
                          false,
                          false,
                          clock.instant())),
                  Map.of()));
    }

    private Evidence evidence(LogCapture logs) {
      var status = port.sourceAccess(ADMIN).getFirst();
      var explanation = port.explainAccess(ADMIN, DOCUMENT, Optional.of("reader")).orElseThrow();
      var user = explanation.user().orElseThrow();
      var captured = logs.events();
      String serialized = captured.toString();
      safeLogs &= !serialized.contains("@example.com") && !serialized.contains("scos_");
      String events =
          captured.stream()
              .filter(event -> event.path("event").asString().startsWith("collection."))
              .map(
                  event ->
                      event.path("event").asString()
                          + (event.path("detail").has("reason")
                              ? ":" + event.path("detail").path("reason").asString()
                              : ""))
              .distinct()
              .sorted()
              .collect(Collectors.joining(","));
      String reason = explanation.acl().orElseThrow().reason().wire();
      return new Evidence(
          status.result().orElseThrow()
              + "|"
              + reason
              + "|"
              + user.keys().size()
              + "|"
              + status.rawStale()
              + "|"
              + (events.isEmpty() ? "none" : events),
          user.visible());
    }

    Map<String, List<Measurement>> measure() {
      var causes = diagnose();
      long started = System.nanoTime();
      var unusedStatus = queries.accessStatus(SOURCE).orElseThrow();
      double statusSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
      started = System.nanoTime();
      port.sourceAccess(ADMIN);
      double adminSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
      return Map.of(
          "P-M3", List.of(new Measurement("sweep", sweepMs, 200, Bound.LT, "ms")),
          "P-M5",
              List.of(
                  new Measurement("status", statusSeconds, 1, Bound.LT, "s/source"),
                  new Measurement("adminSourceAccess", adminSeconds, 1, Bound.LT, "s/source")),
          "O-M1",
              List.of(
                  new Measurement(
                      "distinctCauses",
                      causes.stream().map(Evidence::summary).distinct().count(),
                      3,
                      Bound.EQ,
                      "count"),
                  new Measurement("unobservedWarning", unobserved ? 1 : 0, 1, Bound.EQ, "verified"),
                  new Measurement("safeLogEvidence", safeLogs ? 1 : 0, 1, Bound.EQ, "verified")));
    }

    Map<String, Object> environment() throws Exception {
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
          Map.entry("measuredAt", Instant.now().toString()),
          Map.entry("java", System.getProperty("java.version")),
          Map.entry("os", System.getProperty("os.name")),
          Map.entry(
              "dataset", Map.of("users", 2, "documents", 1, "sources", 1, "providerCalls", 0)),
          Map.entry("approvedMeasurement", false));
    }
  }

  /** 대상 ACL을 누락하는 로컬 공급자: 외부 네트워크와 비밀 입력이 없다. */
  private static final class MissingTargetSource implements SourceAdapter {
    @Override
    public String kind() {
      return "google-drive";
    }

    @Override
    public void validateConfig(SourceConfig config) {}

    @Override
    public CollectionCoverage coverage(
        SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
      return CollectionCoverage.INCREMENTAL;
    }

    @Override
    public void collect(CollectionInput input, PageSink sink) {}

    @Override
    public AccessPolicy accessPolicy() {
      return AccessPolicy.supported(Set.of("gdrive.file"), Set.of("gdrive.file"))
          .collecting((input, sink) -> {});
    }
  }
}
