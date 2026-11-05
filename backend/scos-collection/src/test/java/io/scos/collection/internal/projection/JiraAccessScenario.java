package io.scos.collection.internal.projection;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.jira.internal.FakeJiraServer;
import io.scos.engine.ContextEngine;
import io.scos.engine.DocumentNotFoundException;
import io.scos.engine.ReadRequest;
import io.scos.engine.SearchQuery;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.FakeHttpServer;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestExecutors;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Jira의 실제 조립·PG 수집·투영·제한 검색. HTTP 입력과 관리자 연결만 테스트가 소유한다. */
@Tag("db")
final class JiraAccessScenario implements AutoCloseable {
  private static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
  private static final SpaceId SPACE = new SpaceId("legacy");
  private static final String API = "/rest/api/3";
  private static final String ACCESS_FIELDS = "project,security,reporter,assignee";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DomainPolicy DOMAINS =
      new DomainPolicy(Set.of("example.com"), Map.of(JiraAssembly.SOURCE, "example.com"));
  static final String WIDE =
      "~project:100:e45f76f543e5d9f76f69376b3d417da93faf7c0fafb82e0f6a55986214ae9a03";
  private final MutableClock clock = new MutableClock(T0);
  private final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());
  private final PostgresCollectionConfiguration.Adapters stores =
      PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
  private final JsonNode dataset;
  private final FakeJiraServer jira;
  private final ScriptedEmbedder embedder = new ScriptedEmbedder();
  private final PostgresCollectionRuntime collection;
  private final PostgresProjection projection;
  private final ContextEngine engine;

  JiraAccessScenario() throws IOException {
    PostgresSupport.truncateAll();
    try (var stream = getClass().getResourceAsStream("../jira-access-dataset.json")) {
      if (stream == null) throw new IllegalStateException("Jira 접근 fixture 없음");
      try (var prototype = FakeJiraServer.class.getResourceAsStream("jira-access.json")) {
        if (prototype == null) throw new IllegalStateException("given 준비 실패: Jira 공통 prototype 없음");
        dataset =
            ((ObjectNode) JSON.readTree(prototype)).setAll((ObjectNode) JSON.readTree(stream));
      }
    }
    jira = new FakeJiraServer(dataset);
    tailVisible(false);
    var adapter = JiraAssembly.adapter(jira.baseUrl(), jira.token(), observationClock());
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
                JiraAssembly.SOURCE,
                "jira",
                "{\"siteUrl\":\"https://example.atlassian.net\",\"email\":\"reader@example.com\",\"downloadAttachments\":false}",
                JiraAssembly.CREDENTIAL_REF,
                T0));
    seedAdminLinks();
    engine = restrictedEngine();
  }

  /** 본문과 후속 ACL은 실제로 다른 관측이다. 고정 시계의 같은 시각 상충을 만들지 않는다. */
  private Clock observationClock() {
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return clock.getZone();
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public synchronized Instant instant() {
        clock.advance(Duration.ofMillis(1));
        return clock.instant();
      }
    };
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

  private void seedAdminLinks() {
    for (String id : List.of("U1", "U2", "U3")) {
      jdbc.sql("INSERT INTO users VALUES (:id,:email,true,'member',:at)")
          .param("id", id)
          .param("email", id.toLowerCase(Locale.ROOT) + "@example.com")
          .param("at", Timestamp.from(T0))
          .update();
      jdbc.sql("INSERT INTO user_source_identities VALUES (:source,:id,:id,'admin',:at,:id)")
          .param("source", JiraAssembly.SOURCE.value())
          .param("id", id)
          .param("at", Timestamp.from(T0))
          .update();
    }
  }

  private ObjectNode prototype(String name) {
    return (ObjectNode) dataset.get(name).deepCopy();
  }

  private void answer(String pathAndQuery, int status, String body) {
    String[] parts = pathAndQuery.split("\\?", 2);
    Map<String, String> params = new LinkedHashMap<>();
    if (parts.length == 2) {
      for (String pair : parts[1].split("&", -1)) {
        String[] kv = pair.split("=", 2);
        params.put(kv[0], kv[1]);
      }
    }
    jira.respondExactly(API + parts[0], params, FakeHttpServer.status(status, body));
  }

  void roleUsers(String project, String... users) {
    var actors = JSON.createArrayNode();
    for (String user : users)
      actors.add(
          JSON.createObjectNode()
              .put("type", "atlassian-user-role-actor")
              .set("actorUser", JSON.createObjectNode().put("accountId", user)));
    answer(
        "/project/" + project + "/role/100",
        200,
        prototype("role").set("actors", actors).toString());
  }

  void groupMembers(String... users) {
    var actors =
        JSON.createArrayNode()
            .add(
                JSON.createObjectNode()
                    .put("type", "atlassian-group-role-actor")
                    .set(
                        "actorGroup",
                        JSON.createObjectNode().put("groupId", "G1").put("name", "Viewers")));
    answer("/project/100/role/100", 200, prototype("role").set("actors", actors).toString());
    var members = Arrays.stream(users).map(id -> prototype("user").put("accountId", id)).toList();
    members.forEach(user -> user.remove("emailAddress"));
    answer(
        "/group/member?groupId=G1&includeInactiveUsers=true&startAt=0&maxResults=50",
        200,
        page(members));
  }

  private String page(List<? extends JsonNode> values) {
    return JSON.createObjectNode()
        .put("startAt", 0)
        .put("maxResults", 50)
        .put("total", values.size())
        .put("isLast", true)
        .set("values", JSON.valueToTree(values))
        .toString();
  }

  void security(String... projects) {
    var mappings =
        Arrays.stream(projects)
            .map(id -> prototype("securityMapping").put("projectId", id))
            .toList();
    for (ObjectNode mapping : mappings)
      answer(
          "/issuesecurityschemes/project?projectId="
              + mapping.path("projectId").asString()
              + "&startAt=0&maxResults=50",
          200,
          page(List.of(mapping)));
    answer(
        "/issuesecurityschemes/project?issueSecuritySchemeId=SEC1&startAt=0&maxResults=50",
        200,
        page(mappings));
    answer(
        "/issuesecurityschemes/level?schemeId=SEC1&startAt=0&maxResults=50",
        200,
        page(List.of(prototype("securityLevel"))));
    answer(
        "/issuesecurityschemes/level/member?levelId=L1&startAt=0&maxResults=50",
        200,
        page(List.of(prototype("securityMember"))));
    issueSecurity("10001", "L1");
  }

  void issueSecurity(String issueId, String level) {
    var issue = bodyIssue(issueId);
    ((ObjectNode) issue.path("fields"))
        .set(
            "security",
            level.isEmpty() ? JSON.nullNode() : JSON.createObjectNode().put("id", level));
    answer(
        "/issue/" + issueId + "?fields=" + ACCESS_FIELDS + "&expand=schema", 200, issue.toString());
  }

  private ObjectNode bodyIssue(String id) {
    for (JsonNode route : dataset.path("routes")) {
      if (route.path("path").asString().equals(API + "/issue/" + id)
          && route.path("params").path("fields").asString().equals("*all"))
        return (ObjectNode) route.path("responses").get(0).path("json").deepCopy();
    }
    throw new IllegalArgumentException("given 준비 실패: fixture 이슈 없음 " + id);
  }

  void denyProject(String project) {
    answer("/project/" + project + "/role/100", 403, "{}");
  }

  void teamProject(String project) {
    answer(
        "/project/" + project,
        200,
        prototype("project")
            .put("id", project)
            .put("style", "next-gen")
            .put("simplified", true)
            .toString());
  }

  void conflictingProject(String project) {
    answer(
        "/project/" + project,
        200,
        prototype("project")
            .put("id", project)
            .put("style", "classic")
            .put("simplified", true)
            .toString());
  }

  void companyProject(String project) {
    answer("/project/" + project, 200, prototype("project").put("id", project).toString());
  }

  void policyBlockedArtifact() {
    answer(
        "/dashboard?maxResults=50&startAt=0",
        200,
        "{\"startAt\":0,\"maxResults\":50,\"total\":1,\"dashboards\":[{\"id\":\"D1\",\"name\":\"Deployment\"}]}");
  }

  void givenVisibleIssue() {
    var run = collectAndProject();
    if (embedder.calls.get() == 0
        || run.accessResult() != AccessResult.COMPLETE
        || hits("U1", "10001") != 1
        || hits("U2", "10001") != 1
        || readCount("U1", "10001") != 1
        || readCount("U2", "10001") != 1)
      throw new IllegalStateException("given 준비 실패: CM 기존 권한 " + run);
    embedder.calls.set(0);
  }

  void givenRestrictedIssue(String... projects) {
    security(projects);
    if (List.of(projects).contains("200")) issueSecurity("20001", "L1");
    var run = collectAndProject();
    if (run.accessResult() != AccessResult.COMPLETE
        || hits("U2", "10001") != 1
        || hits("U1", "10001") != 0
        || readCount("U1", "10001") != 0
        || readCount("U2", "10001") != 1
        || !members(WIDE).equals(Set.of("U1", "U2")))
      throw new IllegalStateException("given 준비 실패: 보안 수준과 옛 Browse " + run);
  }

  void givenDeniedIssue() {
    givenVisibleIssue();
    denyProject("100");
    collectAndProject();
    requireFailedIssueWithOldGroup();
  }

  void givenUnknownIssue() {
    givenVisibleIssue();
    conflictingProject("100");
    collectAndProject();
    requireFailedIssueWithOldGroup();
  }

  private void requireFailedIssueWithOldGroup() {
    if (acl("10001").status() != DocumentAcl.Status.FAILED
        || !members(WIDE).equals(Set.of("U1", "U2"))
        || stores.runs().latest(JiraAssembly.SOURCE).orElseThrow().accessResult()
            != AccessResult.INCOMPLETE)
      throw new IllegalStateException("given 준비 실패: 실패 ACL과 보존된 옛 Browse가 필요하다");
  }

  CollectionRun collectAndProject() {
    var run = collect();
    project();
    return run;
  }

  CollectionRun collect() {
    return collect(CollectionMode.INCREMENTAL);
  }

  private CollectionRun collect(CollectionMode mode) {
    clock.advance(Duration.ofSeconds(1));
    collection.build().collect(JiraAssembly.SOURCE, mode, CancellationToken.none());
    return stores.runs().latest(JiraAssembly.SOURCE).orElseThrow();
  }

  void project() {
    var reports =
        projection.run("jira-acl", new ManualTaskScheduler()).runDue(CancellationToken.none());
    if (reports.isEmpty()
        || reports.stream().anyMatch(report -> report.outcome() != ProjectionRun.Outcome.SUCCEEDED))
      throw new IllegalStateException("Jira 투영 실패: " + reports);
  }

  long hits(String user, String issue) {
    return engine.search(new Principal(user), SearchQuery.of("deployment")).hits().stream()
        .map(hit -> hit.documentId())
        .filter(id -> id.equals("issue:" + issue))
        .distinct()
        .count();
  }

  long readCount(String user, String issue) {
    try {
      var document = engine.read(new Principal(user), ReadRequest.of("issue:" + issue));
      return document.units().isEmpty() ? 0 : 1;
    } catch (DocumentNotFoundException denied) {
      return 0;
    }
  }

  DocumentAcl acl(String issue) {
    return new PostgresIndexConfiguration()
        .newAclStore(PostgresSupport.dataSource())
        .get(SPACE, List.of("issue:" + issue))
        .get("issue:" + issue);
  }

  RawAcl.Observed rawAcl(String issue) {
    return stores
        .rawAcls()
        .get(JiraAssembly.SOURCE, List.of("issue:" + issue))
        .get("issue:" + issue);
  }

  Set<String> members(String group) {
    return Set.copyOf(
        jdbc.sql(
                "SELECT unnest(members) FROM source_groups WHERE source_instance_id=:source AND group_id=:group")
            .param("source", JiraAssembly.SOURCE.value())
            .param("group", group)
            .query(String.class)
            .list());
  }

  long groupRows(String group) {
    return jdbc.sql(
            "SELECT count(*) FROM source_groups WHERE source_instance_id=:source AND group_id=:group")
        .param("source", JiraAssembly.SOURCE.value())
        .param("group", group)
        .query(Long.class)
        .single();
  }

  String currentGroup(String issue) {
    return rawAcl(issue).keys().iterator().next().value();
  }

  int embeddingCalls() {
    return embedder.calls.get();
  }

  long bodyChanges() {
    return jdbc.sql("SELECT count(*) FROM raw_changes WHERE source_instance_id=:source")
        .param("source", JiraAssembly.SOURCE.value())
        .query(Long.class)
        .single();
  }

  void givenGroupSharedIssue() {
    groupMembers("U1", "U2");
    givenVisibleIssue();
    if (!members("G1").equals(Set.of("U1", "U2")))
      throw new IllegalStateException("given 준비 실패: 기존 원천 그룹 G1이 필요하다");
  }

  record BodyState(int upserted, int deleted, long changes) {}

  BodyState bodyState(CollectionRun run) {
    return new BodyState(run.upserted(), run.deleted(), bodyChanges());
  }

  record GrantState(AccessResult result, long groups, long documents) {}

  GrantState grantState(String issue, String user) {
    return new GrantState(
        stores.runs().latest(JiraAssembly.SOURCE).orElseThrow().accessResult(),
        groupRows(currentGroup(issue)),
        readCount(user, issue));
  }

  void requireVisible(String user, String issue) {
    if (hits(user, issue) != 1 || readCount(user, issue) != 1)
      throw new IllegalStateException("given 준비 실패: 기존 문서 열람 없음 " + user + " " + issue);
  }

  boolean hiddenIdentityObserved(String user) {
    return jdbc.sql(
                "SELECT count(*) FROM source_identities WHERE source_instance_id=:source AND source_user_id=:user AND email IS NULL AND NOT guest AND NOT deleted")
            .param("source", JiraAssembly.SOURCE.value())
            .param("user", user)
            .query(Long.class)
            .single()
        == 1;
  }

  record BoundState(String key, Set<String> members, long rows, AccessResult result) {}

  void prepareBoundSet(String mode) {
    roleUsers("100", mode.equals("EXPLICIT_EMPTY") ? new String[] {} : new String[] {"U2"});
    if (mode.equals("INCOMPLETE")) denyProject("200");
  }

  BoundState boundState(String issue) {
    String key = currentGroup(issue);
    return new BoundState(
        key,
        members(key),
        groupRows(key),
        stores.runs().latest(JiraAssembly.SOURCE).orElseThrow().accessResult());
  }

  record MergeWindow(
      String rawKey,
      String documentKey,
      boolean observationAdvanced,
      Set<String> oldMembers,
      long newGroupRows,
      boolean collecting) {}

  /** 새 tail의 UNKNOWN 관측은 이전 carrier ACL보다 늦다. oldest-first 선정에서도 마지막이다. */
  private void tailVisible(boolean visible) {
    List<JsonNode> projects = new ArrayList<>();
    for (JsonNode route : dataset.path("routes")) {
      if (!route.path("path").asString().equals(API + "/project/search")) continue;
      for (JsonNode project : route.path("responses").get(0).path("json").path("values"))
        if (visible || !project.path("id").asString().equals("900")) projects.add(project);
    }
    if (projects.size() != (visible ? 3 : 2))
      throw new IllegalStateException("given 준비 실패: 독립 tail 프로젝트 fixture 불일치");
    answer("/project/search?maxResults=50&startAt=0&expand=description", 200, page(projects));
  }

  PausedRun pauseAfterBody() throws Exception {
    stores.outbox().requestProjection(JiraAssembly.SOURCE, clock.instant());
    return new PausedRun("/rest/api/3/project/100/permissionscheme", CollectionMode.SNAPSHOT);
  }

  PausedRun pauseBeforeMerge() throws Exception {
    tailVisible(true);
    return new PausedRun("/rest/api/3/project/900/permissionscheme", CollectionMode.INCREMENTAL);
  }

  final class PausedRun implements AutoCloseable {
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Future<CollectionRun> future;
    private final Instant previousObservation = rawAcl("10001").observedAt();

    private PausedRun(String path, CollectionMode mode) throws Exception {
      var arrived = new CountDownLatch(1);
      jira.gateOn(path, release, ignored -> arrived.countDown());
      future = executor.submit(() -> collect(mode));
      try {
        if (!arrived.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS))
          throw new IllegalStateException("given 준비 실패: Jira HTTP gate 미도착 " + path);
      } catch (Exception | Error failure) {
        try {
          close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        throw failure;
      }
    }

    void requireAclAheadOfMerge(String expectedGroup) {
      project();
      var state = window(expectedGroup);
      var expected =
          new MergeWindow(expectedGroup, expectedGroup, true, Set.of("U1", "U2"), 0, true);
      if (!state.equals(expected))
        throw new IllegalStateException("given 준비 실패: 새 raw/doc 선행과 옛 그룹 보존 " + state);
    }

    MergeWindow window(String expectedGroup) {
      DocumentAcl document = acl("10001");
      return new MergeWindow(
          currentGroup("10001"),
          document.keys().iterator().next().value(),
          rawAcl("10001").observedAt().isAfter(previousObservation),
          members(WIDE),
          groupRows(expectedGroup),
          isCollecting());
    }

    boolean isCollecting() {
      return !future.isDone();
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      try {
        var result = future.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        if (result.status() != RunStatus.SUCCEEDED
            || result.accessResult() != AccessResult.COMPLETE)
          throw new IllegalStateException("Jira gate 해제 뒤 정상 run 마감 실패: " + result);
      } finally {
        terminate();
      }
    }

    private void terminate() throws InterruptedException {
      future.cancel(true);
      executor.shutdownNow();
      if (!executor.awaitTermination(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS))
        throw new IllegalStateException("Jira 수집 future 종료 실패");
    }
  }

  @Override
  public void close() {
    jira.close();
  }
}
