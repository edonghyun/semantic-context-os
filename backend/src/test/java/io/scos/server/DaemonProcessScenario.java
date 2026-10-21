package io.scos.server;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.server.fault.DaemonLoopFault;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.ChildJvm;
import io.scos.support.Eventually;
import io.scos.support.FakeHttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 bootJar 데몬 프로세스 테스트의 연결을 소유한다(ADR-0020 §7): 가짜 Slack·Gemini, PostgreSQL 상태 조회, 자식 JVM 기동·신호,
 * Actuator HTTP 읽기, 조건 대기. 본문은 "어떤 데몬을 띄우고 어떤 신호를 보내 무엇이 남는가"만 쓴다. 만들 때 DB를 비운다.
 */
@Tag("db")
final class DaemonProcessScenario implements AutoCloseable {
  static final Path JAR =
      Path.of(System.getProperty("scos.boot.jar", "build/libs/scos-backend.jar"));
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"));
  static final SourceInstanceId SOURCE = SlackAssembly.SOURCE;
  static final int SIGTERM_EXIT = 143;

  /** Spring 컨텍스트가 뜨지 못해 main이 예외로 끝났을 때의 종료 코드. */
  static final int STARTUP_FAILED_EXIT = 1;

  private static final String HISTORY = "/api/conversations.history";
  private static final String EMBED = "/v1beta/models/gemini-embedding-001:batchEmbedContents";
  private static final int EMBEDDING_DIM = 768;
  private static final Pattern PORT = Pattern.compile("daemon http port=(\\d+)");
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 자식 프로세스 조건 대기 상한(hang 방지) — 기동·종료·투영. 수집 완료는 더 긴 COLLECT_LIMIT. */
  private static final Duration PROCESS_LIMIT = Duration.ofSeconds(60);

  private static final Duration COLLECT_LIMIT = Duration.ofSeconds(90);

  private final Path dir;
  private final FakeSlackServer slack;
  private final FakeHttpServer gemini;
  private final PostgresCollectionConfiguration.Adapters adapters;
  private final JdbcTemplate jdbc;
  private final HttpClient http = HttpClient.newHttpClient();
  private final List<Process> started = new ArrayList<>();
  private CountDownLatch historyGate;
  private CountDownLatch historyArrived;

  DaemonProcessScenario(Path dir) throws IOException {
    this.dir = dir;
    PostgresSupport.truncateAll();
    adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
    jdbc = new JdbcTemplate(PostgresSupport.dataSource());
    slack = new FakeSlackServer(FIXTURES.resolve("slack-dataset.json"));
    gemini = new FakeHttpServer();
    gemini.on(
        "POST",
        EMBED,
        r -> FakeHttpServer.ok(vectors(JSON.readTree(r.body()).get("requests").size())));
    adapters
        .sources()
        .save(SlackAssembly.instance(SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP, Instant.now()));
  }

  // ---- 준비 ----

  /** 같은 데이터셋을 걷는 둘째 Slack 소스(id 순으로 기본 소스 뒤). */
  SourceInstanceId givenSecondSource(String id) {
    SourceInstanceId second = new SourceInstanceId(id);
    adapters
        .sources()
        .save(
            SourceInstances.of(
                second,
                SlackAssembly.KIND,
                SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP,
                SlackAssembly.CREDENTIAL_REF,
                Instant.now()));
    return second;
  }

  /** 자격 증명 파일이 없는 켜진 Slack 소스(경로만 다르고 나머지는 기본 소스와 같다). 그 경로를 돌려준다. */
  Path givenSourceWithMissingCredentialFile(String id) {
    Path missing = dir.resolve("missing-token");
    adapters
        .sources()
        .save(
            SourceInstances.of(
                new SourceInstanceId(id),
                SlackAssembly.KIND,
                SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP,
                "file:" + missing,
                Instant.now()));
    return missing;
  }

  /** 기본 소스의 합성 토큰 값(로그에 새지 않는지 볼 때). */
  String slackToken() {
    return slack.token();
  }

  /** 첫 history 요청이 rate_limited(Retry-After)를 받는다 — 그 대기는 취소로만 깨어난다. */
  void slackRateLimitsFirstHistory(int retryAfterSeconds) {
    slack.failNext(HISTORY, 1, FakeSlackServer.Fault.rateLimited(retryAfterSeconds));
  }

  /** 첫 history 요청이 releaseHistory()까지 응답하지 않는다(그 수집이 점유를 쥔 채 멈춘다). */
  void holdFirstHistory() {
    historyGate = new CountDownLatch(1);
    historyArrived = new CountDownLatch(1);
    slack.gateOn("conversations.history", historyGate, path -> historyArrived.countDown());
  }

  void releaseHistory() {
    historyGate.countDown();
    slack.gateOn("", new CountDownLatch(0), path -> {});
  }

  // ---- 데몬 ----

  Daemon start(String name, Settings settings) {
    Map<String, String> env = env(settings);
    Path log = dir.resolve(name + ".log");
    try {
      Process p =
          settings.loopFault
              ? ChildJvm.startJarWithLoaderPath(JAR, loopFaultLoaderPath(), log, env, "daemon")
              : ChildJvm.startJar(JAR, log, env, "daemon");
      started.add(p);
      return new Daemon(p, log);
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 데몬 설정: 점유 TTL·worker 수·루프 장애 주입. */
  static final class Settings {
    private String leaseTtl = "30s";
    private String concurrency;
    private boolean loopFault;

    static Settings defaults() {
      return new Settings();
    }

    Settings leaseTtl(String ttl) {
      leaseTtl = ttl;
      return this;
    }

    Settings concurrency(int workers) {
      concurrency = String.valueOf(workers);
      return this;
    }

    /** 감독 루프가 치명 오류(Error)로 죽는 장애 주입 조립(테스트 loader.path). */
    Settings fatalLoopFault() {
      loopFault = true;
      return this;
    }
  }

  /** 띄운 데몬 프로세스 하나. */
  final class Daemon {
    private final Process process;
    private final Path log;
    private int port;

    Daemon(Process process, Path log) {
      this.process = process;
      this.log = log;
    }

    /** 포트 0으로 띄운 데몬의 실제 HTTP 포트(기동 로그에서 읽는다). */
    int port() {
      if (port == 0) {
        Eventually.await("HTTP 포트 로그 " + log, () -> PORT.matcher(log()).find(), PROCESS_LIMIT);
        Matcher m = PORT.matcher(log());
        if (!m.find()) throw new IllegalStateException("포트 로그 없음\n" + log());
        port = Integer.parseInt(m.group(1));
      }
      return port;
    }

    String log() {
      try {
        return ChildJvm.readLog(log);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /** SIGTERM을 보내고 종료 코드를 돌려준다. */
    int sigterm() throws InterruptedException {
      process.destroy();
      return awaitExit();
    }

    void kill9() throws InterruptedException {
      process.destroyForcibly();
      if (!process.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("kill -9 뒤 끝나지 않았다");
    }

    /** 스스로 끝나기를 기다려 종료 코드를 돌려준다. */
    int awaitExit() throws InterruptedException {
      if (!process.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("프로세스가 끝나지 않았다\n" + log());
      return process.exitValue();
    }

    Http get(String path) {
      try {
        HttpResponse<String> r =
            http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + path))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        return new Http(
            r.statusCode(), r.body().isEmpty() ? JSON.nullNode() : JSON.readTree(r.body()));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    /** Actuator 지표의 첫 측정값. 태그는 "key:value". */
    double metric(String name, String tag) {
      Http r = get("/actuator/metrics/" + name + (tag == null ? "" : "?tag=" + tag));
      if (r.status() != 200) throw new IllegalStateException("지표 없음: " + name + " " + r);
      return r.body().get("measurements").get(0).get("value").asDouble();
    }

    double metric(String name) {
      return metric(name, null);
    }

    int metricStatus(String name) {
      return get("/actuator/metrics/" + name).status();
    }

    void awaitReady() {
      Eventually.await(
          "readiness ACCEPTING",
          () -> get("/actuator/health/readiness").status() == 200,
          PROCESS_LIMIT);
    }

    void awaitCycles(int n) {
      Eventually.await("사이클 " + n, () -> metric("scos.supervisor.cycle") >= n, PROCESS_LIMIT);
    }
  }

  /** Actuator 응답. 본문 탐색은 업무 질문(상태·구성요소)으로만 연다. */
  record Http(int status, JsonNode body) {
    /** health 응답 최상위 status(UP·DOWN·OUT_OF_SERVICE). */
    String healthStatus() {
      return body.get("status").asString();
    }

    /** health 구성요소(components.{name})의 status. */
    String componentStatus(String name) {
      return body.get("components").get(name).get("status").asString();
    }

    /** health 구성요소(components.{name})의 세부 값. */
    JsonNode componentDetail(String name, String key) {
      return body.get("components").get(name).get("details").get(key);
    }
  }

  // ---- 대기 ----

  void awaitHistoryRequested() {
    Eventually.await(
        "history 요청",
        () -> slack.requests().stream().anyMatch(p -> p.contains("conversations.history")),
        PROCESS_LIMIT);
  }

  void awaitHistoryHeld() throws InterruptedException {
    if (!historyArrived.await(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("history 요청이 게이트에 오지 않았다");
  }

  void awaitLatestRun(SourceInstanceId source, RunStatus status) {
    Eventually.await(
        source.value() + " 최신 run " + status,
        () -> latestRunStatus(source).map(s -> s == status).orElse(false),
        COLLECT_LIMIT);
  }

  void awaitProjectionRequestsDone() {
    Eventually.await("투영 요청 처리", () -> adapters.outbox().pending().isEmpty(), PROCESS_LIMIT);
  }

  // ---- 관찰 ----

  Optional<RunStatus> latestRunStatus(SourceInstanceId source) {
    return adapters.runs().latest(source).map(CollectionRun::status);
  }

  boolean holdsClaim(SourceInstanceId source) {
    return adapters.claims().current(source).isPresent();
  }

  boolean holdsActiveClaim(SourceInstanceId source) {
    return adapters.claims().current(source).filter(c -> c.isActiveAt(Instant.now())).isPresent();
  }

  long rawItems(SourceInstanceId source) {
    return adapters.rawItems().count(source);
  }

  /** 기본 소스 run의 "상태/오류 종류"(시작 순). */
  List<String> runStatuses() {
    return jdbc.queryForList(
        "SELECT status || '/' || coalesce(error_kind, '-') FROM collection_runs WHERE source_instance_id = ? ORDER BY started_at",
        String.class,
        SOURCE.value());
  }

  int runCount(SourceInstanceId source) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM collection_runs WHERE source_instance_id = ?",
        Integer.class,
        source.value());
  }

  /** 소스의 run별 upserted 수(시작 순). */
  List<Integer> upsertedPerRun(SourceInstanceId source) {
    return jdbc.queryForList(
        "SELECT upserted FROM collection_runs WHERE source_instance_id = ? ORDER BY started_at",
        Integer.class,
        source.value());
  }

  @Override
  public void close() {
    for (Process p : started) if (p.isAlive()) p.destroyForcibly();
    if (historyGate != null) historyGate.countDown();
    try {
      slack.assertNoUnmatched();
      gemini.assertNoUnmatched();
    } finally {
      slack.close();
      gemini.close();
    }
  }

  // ---- 연결 ----

  private Map<String, String> env(Settings settings) {
    Map<String, String> env = new HashMap<>();
    env.put("SCOS_DB_URL", PostgresSupport.jdbcUrl());
    env.put("SCOS_DB_USER", PostgresSupport.username());
    env.put("SCOS_DB_PASSWORD", PostgresSupport.password());
    env.put("GEMINI_API_KEY", "unused-in-test");
    env.put("SCOS_GEMINI_BASE_URL", gemini.baseUrl());
    env.put("SCOS_SLACK_BASE_URL", slack.baseUrl());
    env.put(
        SlackAssembly.TOKEN_ENV, slack.token()); // 소스의 credential ref(SlackAssembly.CREDENTIAL_REF)
    env.put("SCOS_LEASE_TTL", settings.leaseTtl);
    env.put("SCOS_DAEMON_PORT", "0");
    env.put("SCOS_DAEMON_POLL", "500ms");
    env.put("SCOS_DAEMON_SHUTDOWN_TIMEOUT", "10s");
    if (settings.concurrency != null) env.put("SCOS_DAEMON_CONCURRENCY", settings.concurrency);
    if (settings.loopFault) env.put("SCOS_TEST_DAEMON_LOOP_FAULT", "error");
    return env;
  }

  /** 장애 주입 조립(DaemonLoopFault)의 클래스만 담은 loader.path 디렉터리. */
  private Path loopFaultLoaderPath() throws IOException, URISyntaxException {
    Path classes =
        Path.of(DaemonLoopFault.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path pkg = Path.of(DaemonLoopFault.class.getPackageName().replace('.', '/'));
    Path target = dir.resolve("loader").resolve(pkg);
    Files.createDirectories(target);
    try (var files = Files.list(classes.resolve(pkg))) {
      for (Path f :
          files.filter(p -> p.getFileName().toString().startsWith("DaemonLoopFault")).toList())
        Files.copy(f, target.resolve(f.getFileName()));
    }
    return dir.resolve("loader");
  }

  /**
   * 데몬 로그(stdout·stderr 합본)의 JSON 줄. JSON이 아닌 줄(JVM 경고 등)이 있으면 그 줄로 실패한다 — 운영 로그는 줄마다 JSON이어야 한다.
   */
  static List<JsonNode> logLines(String log) {
    return log.lines()
        .filter(line -> !line.isBlank())
        .map(
            line -> {
              try {
                return JSON.readTree(line);
              } catch (RuntimeException e) {
                throw new AssertionError("JSON이 아닌 로그 줄: " + line, e);
              }
            })
        .toList();
  }

  private static String vectors(int n) {
    StringBuilder sb = new StringBuilder("{\"embeddings\":[");
    for (int i = 0; i < n; i++) {
      sb.append(i > 0 ? "," : "").append("{\"values\":[");
      for (int d = 0; d < EMBEDDING_DIM; d++) sb.append(d > 0 ? "," : "").append("0.1");
      sb.append("]}");
    }
    return sb.append("]}").toString();
  }
}
