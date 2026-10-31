package io.scos.server;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.server.DaemonProcessScenario.Daemon;
import io.scos.server.DaemonProcessScenario.Settings;
import io.scos.support.Race;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** 실제 jar의 시작·HTTP 왕복·동시 출발을 소유한다. */
@Tag("db")
@Tag("process")
final class DaemonMultiUserHttpScenario implements AutoCloseable {
  private static final Duration REQUEST_LIMIT = Duration.ofSeconds(30);
  private static final int CONCURRENT_REQUESTS = 4;
  private static final Path POOL_REPORT = Path.of("build/reports/daemon-multi-user-pool.json");
  private static final String READ_POOL = "pool:scos-mcp-read";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SEARCH =
      """
      {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_context","arguments":{"query":"정산"}}}
      """;
  private final DaemonProcessScenario daemons;
  private final Daemon daemon;
  private final HttpClient http = HttpClient.newHttpClient();
  private final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());

  DaemonMultiUserHttpScenario(Path dir) throws Exception {
    daemons = new DaemonProcessScenario(dir);
    try {
      daemons.givenMultiUserAccess();
      daemon = daemons.start("multi-user", Settings.defaults().multiUserHttp(availablePort()));
      daemon.awaitReady();
    } catch (Exception | Error failure) {
      close();
      throw failure;
    }
  }

  private int availablePort() throws IOException {
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  List<SearchReply> simultaneousSearches() throws Exception {
    var tokens = List.of(DaemonAccessSeed.ALICE_TOKEN, DaemonAccessSeed.BOB_TOKEN);
    return Race.run(tokens.size(), index -> search(tokens.get(index)));
  }

  /** 기본 크기 읽기 풀을 관찰만 한다. HTTP 표본 사이의 짧은 대기는 측정하지 못한다. */
  List<SearchReply> fourSimultaneousSearches() throws Exception {
    search(DaemonAccessSeed.ALICE_TOKEN);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var work =
          executor.submit(
              () ->
                  Race.run(
                      CONCURRENT_REQUESTS,
                      index ->
                          search(
                              index % 2 == 0
                                  ? DaemonAccessSeed.ALICE_TOKEN
                                  : DaemonAccessSeed.BOB_TOKEN)));
      double maximumPending = 0;
      int samples = 0;
      do {
        maximumPending =
            Math.max(maximumPending, daemon.metric("hikaricp.connections.pending", READ_POOL));
        samples++;
      } while (!work.isDone());
      var replies = work.get(REQUEST_LIMIT.toSeconds(), TimeUnit.SECONDS);
      var observation =
          Map.of(
              "concurrentRequests", CONCURRENT_REQUESTS,
              "readPoolSize", daemon.metric("hikaricp.connections.max", READ_POOL),
              "maximumObservedPending", maximumPending,
              "samples", samples,
              "responses", replies);
      Files.createDirectories(POOL_REPORT.getParent());
      Files.writeString(POOL_REPORT, JSON.writeValueAsString(observation));
      return replies;
    }
  }

  List<HttpReply> unauthorizedRequests() {
    return List.of(
            "",
            DaemonAccessSeed.WRONG_TOKEN,
            DaemonAccessSeed.REVOKED_TOKEN,
            DaemonAccessSeed.INACTIVE_TOKEN)
        .stream()
        .map(token -> post("127.0.0.1", token))
        .toList();
  }

  List<Integer> deactivateBetweenRequests() {
    var before = post("127.0.0.1", DaemonAccessSeed.DEACTIVATED_TOKEN);
    try {
      jdbc.sql("UPDATE users SET active = false WHERE id = :user")
          .param("user", DaemonAccessSeed.DEACTIVATED)
          .update();
      var after = post("127.0.0.1", DaemonAccessSeed.DEACTIVATED_TOKEN);
      return List.of(before.status(), after.status());
    } finally {
      jdbc.sql("UPDATE users SET active = true WHERE id = :user")
          .param("user", DaemonAccessSeed.DEACTIVATED)
          .update();
    }
  }

  List<Integer> actuatorStatuses() {
    return List.of(
        daemon.serverStatus("/actuator/health"), daemon.get("/actuator/health").status());
  }

  HttpReply foreignHost() {
    return post("localhost", DaemonAccessSeed.ALICE_TOKEN);
  }

  HttpReply authenticationStorageFailure() {
    jdbc.sql("ALTER TABLE user_tokens RENAME TO unavailable_user_tokens").update();
    try {
      return post("127.0.0.1", DaemonAccessSeed.ALICE_TOKEN);
    } finally {
      jdbc.sql("ALTER TABLE unavailable_user_tokens RENAME TO user_tokens").update();
    }
  }

  StdioExit refuseStdio() throws Exception {
    var stdio =
        daemons.startMcp("multi-user-stdio", Settings.defaults().multiUserHttp(daemon.port()));
    return new StdioExit(stdio.awaitExit(), stdio.log());
  }

  private SearchReply search(String token) {
    var reply = post("127.0.0.1", token);
    var result = JSON.readTree(reply.body()).path("result");
    if (!result.has("content") || result.path("isError").asBoolean())
      return new SearchReply(reply.status(), Set.of());
    var body = JSON.readTree(result.path("content").get(0).path("text").asString());
    var documents =
        body.path("hits")
            .valueStream()
            .map(hit -> hit.path("documentId").asString())
            .collect(Collectors.toSet());
    return new SearchReply(reply.status(), documents);
  }

  private HttpReply post(String host, String token) {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://" + host + ":" + daemon.port() + McpHttpConfiguration.PATH))
            .timeout(REQUEST_LIMIT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(BodyPublishers.ofString(SEARCH));
    if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
    try {
      HttpResponse<String> reply = http.send(request.build(), BodyHandlers.ofString());
      var headers = new HashMap<>(reply.headers().map());
      // HTTP Date는 요청 시각이며 인증 실패 계약의 일부가 아니다.
      headers.remove("date");
      return new HttpReply(reply.statusCode(), Map.copyOf(headers), reply.body());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  record SearchReply(int status, Set<String> documents) {}

  record HttpReply(int status, Map<String, List<String>> headers, String body) {
    List<String> challenge() {
      return headers.getOrDefault("www-authenticate", List.of());
    }
  }

  record StdioExit(int code, String output) {}

  @Override
  public void close() {
    daemons.close();
    http.close();
  }
}
