package io.scos.collection.internal.projection;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.engine.ContextEngine;
import io.scos.engine.SearchQuery;
import io.scos.engine.SearchResult;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.FakeHttpServer;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestExecutors;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 로컬 Drive HTTP부터 실제 수집·투영·제한 검색까지 잇는다. 관리자 연결만 테스트 시드다. */
@Tag("db")
final class DriveAccessScenario implements AutoCloseable {
  private static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  private static final SpaceId SPACE = new SpaceId("legacy");
  private static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  private static final DomainPolicy DOMAINS =
      new DomainPolicy(Set.of("example.com"), Map.of(SOURCE, "example.com"));
  private final MutableClock clock = new MutableClock(T0);
  private final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());
  private final PostgresCollectionConfiguration.Adapters stores =
      PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
  private final FakeHttpServer server = new FakeHttpServer();
  private final ScriptedEmbedder embedder = new ScriptedEmbedder();
  private final JsonNode dataset;
  private final PostgresCollectionRuntime collection;
  private final PostgresProjection projection;
  private final ContextEngine engine;

  DriveAccessScenario() throws IOException {
    PostgresSupport.truncateAll();
    try (var stream = getClass().getResourceAsStream("../drive-access-dataset.json")) {
      if (stream == null) throw new IllegalStateException("Drive 접근 fixture 없음");
      dataset = new ObjectMapper().readTree(stream);
    }
    serveBody();
    var adapter =
        GoogleDriveAssembly.adapter(server.baseUrl(), GoogleDriveAssembly.CREDENTIAL_JSON, clock);
    collection =
        PostgresCollectionRuntime.over(PostgresSupport.dataSource(), clock)
            .adapter(adapter)
            .collectAccess(true)
            .domains(DOMAINS);
    projection =
        PostgresProjection.collectingAccess(PostgresSupport.dataSource(), clock, embedder, adapter);
    stores
        .sources()
        .save(
            SourceInstances.of(
                SOURCE,
                "google-drive",
                "{\"accountEmail\":\"reader@example.com\",\"permissionVisibility\":\"VERIFIED_FULL\"}",
                GoogleDriveAssembly.CREDENTIAL_REF,
                T0));
    seedUsers();
    engine = restrictedEngine();
    permissions("D1", dataset.get("member"), dataset.get("reader"));
    permissions("P1", dataset.get("reader"));
    permissions("F1", dataset.get("reader"));
  }

  private ContextEngine restrictedEngine() {
    return ContextEngine.builder()
        .units(PostgresTestAdapters.unitStore(PostgresSupport.dataSource()))
        .keywords(PostgresTestAdapters.keywordIndex(PostgresSupport.dataSource()))
        .keywordOnly()
        .access(
            new PostgresIndexConfiguration().newIdentityStore(PostgresSupport.dataSource()),
            PostgresCollectionRuntime.sourceFreshness(PostgresSupport.dataSource()),
            DOMAINS)
        .clock(clock)
        .lexicalExecutor(TestExecutors.CALLER_THREAD)
        .build();
  }

  private void seedUsers() {
    for (String id : List.of("U1", "U2")) {
      String email = id.toLowerCase(Locale.ROOT) + "@example.com";
      jdbc.sql("INSERT INTO users VALUES (:id,:email,true,'member',:at)")
          .param("id", id)
          .param("email", email)
          .param("at", Timestamp.from(T0))
          .update();
      jdbc.sql("INSERT INTO user_source_identities VALUES (:source,:email,:id,'admin',:at,:id)")
          .param("source", SOURCE.value())
          .param("email", email)
          .param("id", id)
          .param("at", Timestamp.from(T0))
          .update();
    }
  }

  private void serveBody() {
    server.requireHeader("Authorization", "Bearer test-access", FakeHttpServer.status(401, "{}"));
    server.withoutHeaderCheck("POST", "/_oauth/token");
    server.on(
        "POST",
        "/_oauth/token",
        request -> FakeHttpServer.ok("{\"access_token\":\"test-access\",\"expires_in\":3600}"));
    server.limitRequests(100, FakeHttpServer.status(400, "{}"));
    reply("/drive/v3/about", dataset.get("about").toString());
    reply("/drive/v3/changes/startPageToken", "{\"startPageToken\":\"start\"}");
    reply("/drive/v3/changes", "{\"changes\":[],\"newStartPageToken\":\"next\"}");
    reply("/drive/v3/files", "{\"files\":[{\"id\":\"F1\"},{\"id\":\"P1\"}]}");
    reply("/drive/v3/drives", "{\"drives\":[" + dataset.get("drive") + "]}");
    reply("/drive/v3/files/F1", dataset.get("file").toString());
    reply("/drive/v3/files/P1", dataset.get("folder").toString());
    reply("/drive/v3/files/F1/comments", "{\"comments\":[]}");
    server.on(
        "GET",
        "/_docs/v1/documents/F1",
        request -> FakeHttpServer.ok(dataset.get("document").toString()));
  }

  private void reply(String path, String body) {
    server.on("GET", "/_drive" + path, request -> FakeHttpServer.ok(body));
  }

  private void permissions(String id, JsonNode... grants) {
    String body =
        "{\"permissions\":["
            + String.join(",", Arrays.stream(grants).map(Object::toString).toList())
            + "]}";
    server.on(
        "GET",
        "/_drive/drive/v3/files/" + id + "/permissions",
        request -> {
          clock.advance(Duration.ofMillis(1));
          return FakeHttpServer.ok(body);
        });
  }

  void givenRestrictedChild() {
    collect();
    project();
    requireVisible("U2");
    var keys =
        new PostgresIndexConfiguration()
            .newIdentityStore(PostgresSupport.dataSource())
            .accessKeysOf("U1", DOMAINS)
            .orElseThrow();
    if (keys.stream().noneMatch(key -> key.toString().equals("user:drive-a:u1@example.com")))
      throw new IllegalStateException("given 준비 실패: 관리자 연결된 Drive 멤버 신원 없음");
    if (new PostgresIndexConfiguration()
            .newAclStore(PostgresSupport.dataSource())
            .get(SPACE, List.of("drive:D1"))
            .get("drive:D1")
            .keys()
            .stream()
            .noneMatch(key -> key.toString().equals("user:drive-a:u1@example.com")))
      throw new IllegalStateException("given 준비 실패: D1 멤버 U1 권한 없음");
    embedder.calls.set(0);
  }

  void givenDomainSharedChild() {
    permissions("P1", dataset.get("reader"), dataset.get("domain"));
    permissions("F1", dataset.get("reader"), dataset.get("domain"));
    givenRestrictedChild();
    requireVisible("U1");
  }

  void givenGroupSharedChild() {
    permissions("F1", dataset.get("reader"), dataset.get("group"));
    givenRestrictedChild();
  }

  private void requireVisible(String user) {
    var result = search(user);
    if (result.hits().size() != 1)
      throw new IllegalStateException("given 준비 실패: F1이 보여야 한다 — " + user + " " + result);
  }

  void revokeFolderDomain() {
    permissions("P1", dataset.get("reader"));
    permissions("F1", dataset.get("reader"));
  }

  void readerCannotReadPermissions() {
    server.on(
        "GET",
        "/_drive/drive/v3/files/F1/permissions",
        request ->
            FakeHttpServer.status(
                403,
                "{\"error\":{\"code\":403,\"errors\":[{\"domain\":\"global\",\"reason\":\"insufficientFilePermissions\"}]}}"));
  }

  void failPermissions() {
    server.on(
        "GET",
        "/_drive/drive/v3/files/F1/permissions",
        request -> FakeHttpServer.status(404, "{\"error\":{\"code\":404}}"));
  }

  CollectionRun collectAndProject() {
    var run = collect();
    project();
    return run;
  }

  CollectionRun collect() {
    clock.advance(Duration.ofSeconds(1));
    collection.build().collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    return stores.runs().latest(SOURCE).orElseThrow();
  }

  void project() {
    var reports =
        projection.run("drive-acl", new ManualTaskScheduler()).runDue(CancellationToken.none());
    if (reports.stream().anyMatch(report -> report.outcome() != ProjectionRun.Outcome.SUCCEEDED))
      throw new IllegalStateException("Drive 투영 실패: " + reports);
  }

  SearchResult search(String user) {
    return engine.search(new Principal(user), SearchQuery.of("deployment"));
  }

  void givenPendingProjection() {
    stores.outbox().requestProjection(SOURCE, clock.instant());
  }

  long requestVersion() {
    return stores.outbox().request(SOURCE).map(OutboxPort.Request::version).orElse(0L);
  }

  long bodyChanges() {
    return jdbc.sql("SELECT count(*) FROM raw_changes WHERE source_instance_id=:source")
        .param("source", SOURCE.value())
        .query(Long.class)
        .single();
  }

  record BodyState(int upserted, int deleted, long changes) {}

  BodyState bodyState(CollectionRun run) {
    return new BodyState(run.upserted(), run.deleted(), bodyChanges());
  }

  boolean bodyPreserved() {
    return stores.rawItems().get(SOURCE, "file:F1").orElseThrow() instanceof RawRecord.Active
        && storedBodyUnits() > 0;
  }

  DocumentAcl fileAcl() {
    return new PostgresIndexConfiguration()
        .newAclStore(PostgresSupport.dataSource())
        .get(SPACE, List.of("file:F1"))
        .get("file:F1");
  }

  int embeddingCalls() {
    return embedder.calls.get();
  }

  private int storedBodyUnits() {
    return jdbc.sql("SELECT count(*)::int FROM units WHERE document_id='file:F1'")
        .query(Integer.class)
        .single();
  }

  @Override
  public void close() {
    server.close();
  }
}
