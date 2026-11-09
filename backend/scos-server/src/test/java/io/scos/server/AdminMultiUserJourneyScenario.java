package io.scos.server;

import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.admin.IssuedToken;
import io.scos.server.DaemonMultiUserHttpScenario.HttpReply;
import io.scos.server.DaemonProcessScenario.Daemon;
import io.scos.server.DaemonProcessScenario.Settings;
import io.scos.support.Eventually;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 실제 JAR·관리 HTTP·PG를 잇는다. 관리 상태는 bootstrap/REST/run 마감으로만 만든다. */
@Tag("db")
@Tag("process")
final class AdminMultiUserJourneyScenario implements AutoCloseable {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration RUN_LIMIT = Duration.ofSeconds(90);
  private final Path directory;
  private final DaemonProcessScenario processes;
  private final HttpClient http = HttpClient.newHttpClient();
  private Daemon daemon;
  private String administratorToken;
  private int collections;

  AdminMultiUserJourneyScenario(Path directory) throws Exception {
    this.directory = Files.createDirectories(directory);
    processes = new DaemonProcessScenario(directory, slackDataset());
    new DaemonAccessSeed(PostgresSupport.dataSource()).seedJourneyDocuments();
  }

  void bootstrap() throws Exception {
    bootstrap(Mode.MULTI_USER);
  }

  void bootstrap(Mode mode) throws Exception {
    Path file = directory.resolve("bootstrap-token");
    int exit =
        processes.command(
            "bootstrap",
            Settings.defaults(),
            "admin",
            "bootstrap",
            "--email",
            "admin@example.com",
            "--token-file",
            file.toString());
    if (exit != 0)
      throw new IllegalStateException(
          "given 준비 실패: bootstrap exit="
              + exit
              + "; console="
              + directory.resolve("bootstrap.console.log"));
    administratorToken = Files.readString(file).strip();
    int port = DaemonMultiUserHttpScenario.availablePort();
    var settings =
        mode == Mode.SINGLE_USER
            ? Settings.defaults().singleUserAdminHttp(port)
            : Settings.defaults().multiUserHttp(port);
    daemon =
        processes.start(
            "journey",
            settings.organizationDomain("example.com").manualCollectionsAfterInitialRun());
    daemon.awaitReady();
    processes.awaitLatestRun(DaemonProcessScenario.SOURCE, RunStatus.SUCCEEDED_WITH_REJECTIONS);
    Eventually.await(
        "초기 수집 점유 해제", () -> !processes.holdsActiveClaim(DaemonProcessScenario.SOURCE), RUN_LIMIT);
  }

  Account createMember(String email) {
    var user = require(request("POST", "/users", Map.of("email", email, "role", "member")), 201);
    String id = user.path("id").asString();
    var token =
        require(request("POST", "/users/" + id + "/tokens", Map.of("label", "journey")), 201);
    return new Account(id, issuedToken(token));
  }

  void link(Account account, String identity) {
    require(request("PUT", "/identities/slack-a/" + identity, Map.of("userId", account.id())), 204);
  }

  void designate() {
    require(request("PUT", "/scopes/notion-a", Map.of()), 200);
  }

  /** 이메일을 바꾼 실제 collect 명령은 run 마감과 자동 연결까지 동기 실행한다. */
  void collect(String email) throws Exception {
    processes.changeSlackAccess(
        access ->
            ((ObjectNode) access.path("userPages").get(0).get(0).path("profile"))
                .put("email", email));
    int previous = processes.runCount(DaemonProcessScenario.SOURCE);
    String commandName = "collect-" + collections++;
    int exit =
        processes.command(
            commandName,
            Settings.defaults().organizationDomain("example.com"),
            "collect",
            "--source=slack-synthetic");
    if (exit != 0 || processes.runCount(DaemonProcessScenario.SOURCE) != previous + 1)
      throw new IllegalStateException(
          "given 준비 실패: 독립 collect run이 완료돼야 한다; exit="
              + exit
              + "; console="
              + directory.resolve(commandName + ".console.log")
              + "; daemon="
              + directory.resolve("journey.console.log"));
    processes.awaitLatestRun(DaemonProcessScenario.SOURCE, RunStatus.SUCCEEDED_WITH_REJECTIONS);
    processes.awaitProjectionRequestsDone();
    var sources = require(request("GET", "/sources/access", Map.of()), 200);
    boolean complete =
        sources
            .valueStream()
            .anyMatch(
                source ->
                    source.path("source").path("value").asString().equals("slack-synthetic")
                        && source.path("result").asString().equals("complete"));
    // CLI는 run 마감 후 reconcile까지 동기 실행한다. 실제 진단 빈에서 접근 완료도 확인한다.
    if (!complete) throw new IllegalStateException("given 준비 실패: 접근 run이 complete여야 한다");
  }

  HttpReply request(String method, String path, Map<String, Object> body) {
    return send(administratorToken, method, "/admin/v1" + path, body);
  }

  ToolReply request(Account account, String tool, Map<String, Object> arguments) {
    return new ToolReply(
        send(
            account.token().value(),
            "POST",
            "/mcp",
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                1,
                "method",
                "tools/call",
                "params",
                Map.of("name", tool, "arguments", arguments))));
  }

  private HttpReply send(String token, String method, String path, Map<String, Object> body) {
    return DaemonMultiUserHttpScenario.request(
        http,
        daemon.port(),
        "127.0.0.1",
        token,
        method,
        path,
        body.isEmpty() ? "" : JSON.writeValueAsString(body));
  }

  private JsonNode require(HttpReply reply, int status) {
    if (reply.status() != status)
      throw new IllegalStateException(
          "given 준비 실패: 관리 HTTP "
              + reply.status()
              + " != "
              + status
              + "; console="
              + directory.resolve("journey.console.log"));
    return reply.body().isEmpty() ? JSON.nullNode() : JSON.readTree(reply.body());
  }

  static IssuedToken issuedToken(JsonNode body) {
    return new IssuedToken(
        UUID.fromString(body.path("tokenId").asString()), body.path("value").asString());
  }

  private JsonNode slackDataset() throws Exception {
    ObjectNode dataset =
        (ObjectNode)
            JSON.readTree(
                Files.readString(DaemonProcessScenario.FIXTURES.resolve("slack-dataset.json")));
    ObjectNode access = dataset.putObject("access");
    access.put("teamId", "T1");
    ObjectNode conversations = access.putObject("conversations");
    ObjectNode members = access.putObject("memberPages");
    for (JsonNode id : dataset.path("channels")) {
      conversations
          .putObject(id.asString())
          .put("id", id.asString())
          .put("is_private", false)
          .put("is_im", false)
          .put("is_mpim", false)
          .put("is_archived", false);
      members.putArray(id.asString()).addArray().add("U01").add("U02");
    }
    var users = access.putArray("userPages").addArray();
    for (String id : List.of("U01", "U02")) {
      users
          .addObject()
          .put("id", id)
          .put("team_id", "T1")
          .put("deleted", false)
          .put("is_bot", false)
          .put("is_restricted", false)
          .put("is_ultra_restricted", false)
          .put("is_stranger", false)
          .put("is_email_confirmed", true)
          .putObject("profile")
          .put("email", id.equals("U01") ? "alice@example.com" : "bob@example.com");
    }
    return dataset;
  }

  enum Mode {
    SINGLE_USER,
    MULTI_USER
  }

  record Account(String id, IssuedToken token) {}

  record ToolReply(HttpReply http) {
    JsonNode result() {
      return JSON.readTree(http.body()).path("result");
    }

    JsonNode content() {
      if (http.status() != 200 || result().path("isError").asBoolean())
        throw new IllegalStateException("given 준비 실패: MCP 도구 성공 응답이 필요하다");
      return JSON.readTree(result().path("content").get(0).path("text").asString());
    }

    HttpReply withoutReference() {
      return new HttpReply(
          http.status(),
          http.headers(),
          http.body().replaceAll("\\(참조 [0-9a-f]{8}-[0-9a-f]{3}\\)", "(참조 <ref>)"));
    }

    void requireDocument(String document) {
      if (!documents().contains(document))
        throw new IllegalStateException("given 준비 실패: 허용 문서가 먼저 보여야 한다: " + document);
    }

    Set<String> documents() {
      return content()
          .path("hits")
          .valueStream()
          .map(hit -> hit.path("documentId").asString())
          .collect(Collectors.toSet());
    }
  }

  @Override
  public void close() {
    processes.close();
    http.close();
  }
}
