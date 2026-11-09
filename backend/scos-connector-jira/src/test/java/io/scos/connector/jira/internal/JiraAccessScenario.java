package io.scos.connector.jira.internal;

import io.scos.collection.internal.RawContentCapture;
import io.scos.connector.jira.JiraAdapter;
import io.scos.connector.jira.JiraAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RecordedAclSink;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.FakeHttpServer;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 실제 Jira HTTP·호출 실행기를 접근 디렉터리에 연결한다. 등록되지 않은 쿼리도 실패시킨다. */
final class JiraAccessScenario implements AutoCloseable {
  static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();
  private final MutableClock clock = new MutableClock(NOW);
  private final FakeHttpServer http;
  private final Map<String, Map<Map<String, String>, FakeHttpServer.Response>> routes =
      new LinkedHashMap<>();
  private final List<String> unmatched = new ArrayList<>();
  private static final JsonNode FIXTURE = readFixture();
  private final CancellationToken cancel = new CancellationToken();
  private final JiraAccessDirectory directory;
  private final JiraAdapter adapter;
  private final List<String> credentialRefs = new ArrayList<>();
  private final List<RawAcl> bodyAcls = new ArrayList<>();
  private SourceConfig config;
  private final List<AclPage> pages = new ArrayList<>();
  private final Map<String, String> stored = new LinkedHashMap<>();
  private Runnable afterPage = () -> {};

  JiraAccessScenario() {
    try {
      http = new FakeHttpServer();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    http.requireHeader(
        "Authorization",
        "Basic cmVhZGVyQGV4YW1wbGUuY29tOnRlc3QtdG9rZW4=",
        FakeHttpServer.status(401, "{}"));
    http.limitRequests(64, FakeHttpServer.status(400, "{}"));
    config =
        new SourceConfig(
            1, "{\"siteUrl\":\"https://example.atlassian.net\",\"email\":\"reader@example.com\"}");
    directory =
        new JiraAccessDirectory(
            accessInput(config),
            JiraConfiguration.read(JSON, config),
            "test-token",
            JiraAssembly.client(http.baseUrl(), clock),
            clock);
    adapter =
        new JiraAdapter(
            ref -> {
              credentialRefs.add(ref);
              return "test-token";
            },
            JiraAssembly.client(http.baseUrl(), clock),
            JSON,
            clock);
    companyProject("P1");
    browse("P1", "{\"type\":\"user\",\"parameter\":\"U1\"}");
    noSecurity("P1");
    user("U1");
    user("U2");
    user("U3");
    user("U4");
  }

  private static JsonNode readFixture() {
    try (var stream = JiraAccessScenario.class.getResourceAsStream("jira-access.json")) {
      if (stream == null) throw new IllegalStateException("given 준비 실패: Jira ACL fixture 없음");
      return JSON.readTree(stream);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private ObjectNode prototype(String name) {
    return (ObjectNode) FIXTURE.get(name).deepCopy();
  }

  private AccessInput accessInput(SourceConfig config) {
    var source =
        new SourceDescriptor(
            new SourceInstanceId("jira-a"), "jira", "Jira", config, "env:TEST_ONLY");
    var calls =
        new ProviderCallExecutor(
            ProviderCallPolicy.defaults(() -> 0.0),
            clock,
            (delay, token) -> token.isCancelled(),
            TestMetrics.providerCalls());
    return new AccessInput(
        source,
        List.of(),
        calls,
        cancel,
        new DomainPolicy(Set.of("example.com"), Map.of(source.id(), "example.com")));
  }

  void companyProject(String id) {
    answer("/project/" + id, prototype("project").put("id", id).put("key", id).toString());
    noSecurity(id);
  }

  void browse(String project, String... holders) {
    answer(
        "/project/" + project + "/permissionscheme",
        prototype("schemeReference").put("id", "S" + project).toString());
    var permissions = JSON.createArrayNode();
    for (String holder : holders)
      permissions.add(prototype("permission").set("holder", JSON.readTree(holder)));
    var scheme = prototype("scheme").put("id", "S" + project);
    scheme.set("permissions", permissions);
    answer("/permissionscheme/S" + project + "?expand=permissions", scheme.toString());
  }

  void noSecurity(String project) {
    answer(
        "/issuesecurityschemes/project?projectId=" + project + "&startAt=0&maxResults=50",
        page(0, 0, ""));
  }

  void security(String... projects) {
    var mappings = new ArrayList<String>();
    for (String project : projects) {
      String mapping = prototype("securityMapping").put("projectId", project).toString();
      mappings.add(mapping);
      answer(
          "/issuesecurityschemes/project?projectId=" + project + "&startAt=0&maxResults=50",
          page(0, 1, mapping));
    }
    answer(
        "/issuesecurityschemes/project?issueSecuritySchemeId=SEC1&startAt=0&maxResults=50",
        page(0, projects.length, String.join(",", mappings)));
    answer(
        "/issuesecurityschemes/level?schemeId=SEC1&startAt=0&maxResults=50",
        page(0, 1, FIXTURE.get("securityLevel").toString()));
    levelMembers(
        "{\"type\":\"user\",\"parameter\":\"U2\"}", "{\"type\":\"user\",\"parameter\":\"U4\"}");
  }

  void levelMembers(String... holders) {
    var members = new ArrayList<String>();
    int id = 0;
    for (String holder : holders)
      members.add(
          prototype("securityMember")
              .put("id", "M" + id++)
              .set("holder", JSON.readTree(holder))
              .toString());
    answer(
        "/issuesecurityschemes/level/member?levelId=L1&startAt=0&maxResults=50",
        page(0, holders.length, String.join(",", members)));
  }

  void user(String id) {
    var user = FIXTURE.get("user").deepCopy();
    ((ObjectNode) user).put("accountId", id);
    answer("/user?accountId=" + id, user.toString());
  }

  void userResponse(String id, String body) {
    answer("/user?accountId=" + id, body);
  }

  String userJson(String id) {
    return prototype("user").put("accountId", id).toString();
  }

  String userWithEmail(String id, String email) {
    return prototype("user").put("accountId", id).put("emailAddress", email).toString();
  }

  void responseShape(boolean recorded) {
    var sample =
        recorded ? recordedResponses() : JSON.createObjectNode().set("user", prototype("user"));
    userResponse("U1", sample.path("user").toString());
  }

  void teamManagedResponseShape(boolean recorded) {
    String body =
        recorded
            ? recordedResponses().path("project").toString()
            : prototype("project").put("style", "next-gen").put("simplified", true).toString();
    answer("/project/P1", body);
  }

  void issueResponseShape(boolean recorded) {
    JsonNode body = recorded ? recordedResponses().path("issue") : prototype("issue");
    answer("/issue/10001?fields=project,security,reporter,assignee&expand=schema", body.toString());
  }

  private JsonNode recordedResponses() {
    try (var stream = getClass().getResourceAsStream("recorded/access-responses.json")) {
      if (stream == null) throw new IllegalStateException("given 준비 실패: Jira 기록 표본 없음");
      return JSON.readTree(stream);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  String inactiveUser(String id) {
    return prototype("user").put("accountId", id).put("active", false).toString();
  }

  String appUser(String id) {
    return prototype("user").put("accountId", id).put("accountType", "app").toString();
  }

  String customerUser(String id) {
    return prototype("user").put("accountId", id).put("accountType", "customer").toString();
  }

  void role(String project, String... actors) {
    var role = prototype("role");
    role.set("actors", JSON.readTree("[" + String.join(",", actors) + "]"));
    answer("/project/" + project + "/role/100", role.toString());
  }

  void groupPages(String first, String last) {
    answer("/group/member?groupId=G1&includeInactiveUsers=true&startAt=0&maxResults=50", first);
    answer("/group/member?groupId=G1&includeInactiveUsers=true&startAt=1&maxResults=50", last);
  }

  void longGroup(int total) {
    http.limitRequests(1100, FakeHttpServer.status(400, "{}"));
    http.on(
        "GET",
        "/rest/api/3/group/member",
        request -> {
          int offset = Integer.parseInt(request.params().get("startAt"));
          Map<String, String> expected =
              Map.of(
                  "groupId",
                  "G1",
                  "includeInactiveUsers",
                  "true",
                  "startAt",
                  Integer.toString(offset),
                  "maxResults",
                  "50");
          if (!request.params().equals(expected) || offset < 0 || offset >= total)
            throw new AssertionError("미등록 긴 그룹 요청: " + request.params());
          return new FakeHttpServer.Response(
              200, page(offset, total, userJson("U" + offset)), Map.of("Connection", "close"));
        });
  }

  void answer(String path, String body) {
    response(path, 200, body);
  }

  void response(String path, int status, String body) {
    String[] parts = path.split("\\?", 2);
    var query = new LinkedHashMap<String, String>();
    if (parts.length == 2)
      for (String pair : parts[1].split("&", -1)) {
        String[] kv = pair.split("=", 2);
        query.put(kv[0], kv[1]);
      }
    String full = "/rest/api/3" + parts[0];
    routes
        .computeIfAbsent(full, ignored -> new LinkedHashMap<>())
        .put(Map.copyOf(query), FakeHttpServer.status(status, body));
    http.on(
        "GET",
        full,
        request -> {
          var result = routes.get(full).get(request.params());
          if (result != null) return result;
          unmatched.add(full + request.params());
          return FakeHttpServer.status(400, "{}");
        });
  }

  static String page(int start, int total, String users) {
    return String.format(
        Locale.ROOT,
        FIXTURE.get("pageTemplate").asString(),
        start,
        total,
        start + JSON.readTree("[" + users + "]").size() == total,
        users);
  }

  void issue(String id, String project, String security) {
    var issue = prototype("issue").put("id", id);
    var fields = (ObjectNode) issue.path("fields");
    fields.set("project", prototype("project").put("id", project));
    fields.remove("security");
    if (!security.isEmpty())
      fields.set("security", JSON.readTree("{" + security.substring(1) + "}").path("security"));
    answer(
        "/issue/" + id + "?fields=project,security,reporter,assignee&expand=schema",
        issue.toString());
  }

  void customIssue(String field, String schema, String value) {
    issue("10001", "P1", ",\"security\":null");
    var issue = prototype("issue");
    ((ObjectNode) issue.path("fields")).set(field, JSON.readTree(value));
    ((ObjectNode) issue.path("schema")).set(field, JSON.readTree(schema));
    answer(
        "/issue/10001?fields=project,security,reporter,assignee," + field + "&expand=schema",
        issue.toString());
  }

  void teamProject(String id) {
    answer(
        "/project/" + id,
        prototype("project")
            .put("id", id)
            .put("style", "next-gen")
            .put("simplified", true)
            .toString());
  }

  void storedIssue(String id, String project, boolean hasSecurity) {
    var issue = prototype("issue").put("id", id);
    ((ObjectNode) issue.path("fields").path("project")).put("id", project);
    if (!hasSecurity) ((ObjectNode) issue.path("fields")).remove("security");
    stored.put("issue:" + id, issue.toString());
  }

  void storedContent(String id, String value) {
    stored.put(id, value);
  }

  void unknownProject(String id) {
    var project = prototype("project").put("id", id);
    project.remove("style");
    answer("/project/" + id, project.toString());
  }

  void collectBodyAndAccess() {
    answer(
        "/field?maxResults=50&startAt=0",
        "[{\"id\":\"summary\",\"name\":\"Summary\",\"custom\":false,\"schema\":{\"type\":\"string\",\"system\":\"summary\"}}]");
    var input = accessInput(config);
    var checkpoint =
        new Checkpoint(
            1,
            "{\"siteUrl\":\"https://example.atlassian.net\",\"pending\":[{\"kind\":\"fields\",\"start\":0}],\"downloadedAttachments\":[]}");
    adapter.collect(
        new CollectionInput(
            input.source(),
            Optional.of(checkpoint),
            CollectionMode.INCREMENTAL,
            input.calls(),
            RawContentCapture.defaults(),
            cancel),
        page -> {
          for (RawMutation mutation : page.mutations())
            if (mutation instanceof RawMutation.Upsert upsert) bodyAcls.add(upsert.item().acl());
        });
    collect("project:P1");
  }

  List<String> credentialRefs() {
    return List.copyOf(credentialRefs);
  }

  List<RawAcl> bodyAcls() {
    return List.copyOf(bodyAcls);
  }

  List<String> grantGroupIds() {
    return pages.stream()
        .flatMap(p -> p.groups().stream())
        .map(g -> g.groupId())
        .distinct()
        .toList();
  }

  void issueField(String field, String value) {
    var issue = prototype("issue");
    ((ObjectNode) issue.path("fields")).set(field, JSON.readTree(value));
    answer(
        "/issue/10001?fields=project,security,reporter,assignee&expand=schema", issue.toString());
  }

  Set<String> identityIds() {
    return pages.stream()
        .flatMap(p -> p.identities().stream())
        .map(i -> i.sourceUserId())
        .collect(Collectors.toSet());
  }

  void artifact(String kind, String id, String project) {
    answer(
        "/" + kind + "/" + id,
        "{\"id\":\"" + id + "\",\"name\":\"Release\",\"projectId\":\"" + project + "\"}");
  }

  void collect(String... ids) {
    var input = accessInput(config);
    var targets =
        new AccessInput(
            input.source(),
            List.of(ids),
            input.calls(),
            cancel,
            input.domains(),
            id -> Optional.ofNullable(stored.get(id)));
    adapter
        .accessPolicy()
        .collect(
            targets,
            new RecordedAclSink(
                page -> {
                  pages.add(page);
                  afterPage.run();
                }));
  }

  Set<String> observedIds() {
    return pages.stream().flatMap(p -> p.acls().keySet().stream()).collect(Collectors.toSet());
  }

  void cancelAfterPage() {
    afterPage = cancel::cancel;
  }

  void collectInterruptedOnLastPage() {
    Thread collecting = Thread.currentThread();
    afterPage = collecting::interrupt;
    try {
      collect("filter:1");
    } finally {
      Thread.interrupted();
    }
  }

  RawAcl.Observed acl(String id) {
    return pages.stream()
        .flatMap(p -> p.acls().entrySet().stream())
        .filter(e -> e.getKey().equals(id))
        .map(e -> (RawAcl.Observed) e.getValue())
        .reduce((first, last) -> last)
        .orElseThrow();
  }

  Set<String> keys(String id) {
    return acl(id).keys().stream().map(AccessKey::toString).collect(Collectors.toSet());
  }

  JiraAccessDirectory.ProjectAccess readProject(String id) {
    return directory.readProject(id);
  }

  void requireComplete() {
    directory.requireComplete();
  }

  List<String> observedGroups(String id) {
    List<String> result = new ArrayList<>();
    directory
        .readProject(id)
        .emitTo(
            new RecordedAclSink(
                page -> page.groups().forEach(group -> result.add(group.groupId()))));
    return List.copyOf(result);
  }

  long requestsTo(String path) {
    return http.received().stream().filter(r -> r.path().equals("/rest/api/3" + path)).count();
  }

  void advanceObservation() {
    clock.advance(Duration.ofSeconds(1));
  }

  void cancel() {
    cancel.cancel();
  }

  void cancelDuringLevelObservation() {
    http.on(
        "GET",
        "/rest/api/3/issuesecurityschemes/project",
        request -> {
          cancel.cancel();
          return FakeHttpServer.status(503, "{}");
        });
  }

  @Override
  public void close() {
    try {
      http.assertNoUnmatched();
      if (!unmatched.isEmpty()) throw new AssertionError("미등록 쿼리: " + unmatched);
    } finally {
      http.close();
    }
  }
}
