package io.scos.connector.jira.internal;

import io.scos.collection.internal.credentials.SchemeCredentialResolver;
import io.scos.connector.jira.JiraAssembly;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.SourceConfig;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/**
 * Jira 클라이언트 테스트의 연결: 데이터셋 가짜 Jira, 고정 시각(fixture NOW — HTTP-date Retry-After 기준), 데이터셋 설정. 본문은 "어떤
 * 응답에 무엇을 부르면 무엇이 나오는가"만 쓴다. 닫을 때 미등록 요청이 있으면 실패한다.
 */
final class JiraClientScenario implements AutoCloseable {
  static final Duration TIMEOUT = Duration.ofSeconds(10);
  static final long ROOMY_LIMIT = 1024 * 1024;
  static final String FIELDS = "/rest/api/3/field";
  static final String FIELDS_PAGE = FIELDS + "?maxResults=50&startAt=0";
  static final String DASHBOARDS_PAGE = "/rest/api/3/dashboard?maxResults=50&startAt=0";
  static final String ATTACHMENT = "/rest/api/3/attachment/content/";

  /** 리다이렉트가 가리키는 다른 경로(따라가면 요청이 기록된다). */
  static final String ELSEWHERE = "/rest/api/3/elsewhere";

  static final Instant NOW = Instant.parse(JiraFixtures.client().get("now").asString());

  /** 동시에 진행해야 하는 요청 수(TS MAX_CONCURRENT_READS). */
  static final int CONCURRENT_READS = 4;

  private final FakeJiraServer jira;
  private final JiraApiClient client;
  private final JiraConfiguration config;

  JiraClientScenario() {
    try {
      jira = new FakeJiraServer(JiraFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    client = JiraAssembly.client(jira.baseUrl(), Clock.fixed(NOW, ZoneOffset.UTC));
    config =
        JiraConfiguration.read(JiraFixtures.JSON, new SourceConfig(1, JiraAssembly.SYNTHETIC_SITE));
  }

  // ---- 준비 ----

  /** 필드 목록 경로가 이 상태(와 Retry-After)로 답한다. 본문은 토큰·내부 정보가 담긴 것처럼 보이는 문자열이다. */
  void fieldsAnswer(int status, Optional<String> retryAfter) {
    jira.respond(
        FIELDS,
        new Response(
            status,
            "private response " + jira.token(),
            retryAfter.map(v -> Map.of("Retry-After", v)).orElse(Map.of())));
  }

  void fieldsAnswerRaw(Response response) {
    jira.respond(FIELDS, response);
  }

  /** 필드 목록이 같은 가짜 서버의 다른 경로(ELSEWHERE)로 302를 답한다. 그 경로는 따라오면 200으로 답한다. */
  void fieldsRedirectElsewhere() {
    jira.respond(ELSEWHERE, new Response(200, "[]", Map.of()));
    jira.respond(FIELDS, new Response(302, "", Map.of("Location", jira.baseUrl() + ELSEWHERE)));
  }

  /** 이 경로로 받은 요청 수. */
  long requestsTo(String path) {
    return jira.requests().stream().filter(r -> r.path().equals(path)).count();
  }

  /** 필드 목록 요청은 넷이 함께 처리 중이 될 때까지(상한까지) 답하지 않는다 — 동시에 처리 중이던 최대 수를 센다. */
  void fieldsHeldUntilFourInFlight() {
    jira.respond(FIELDS, new Response(200, "[]", Map.of()));
    jira.holdUntilInFlight(FIELDS, CONCURRENT_READS);
  }

  /** 필드 목록 요청이 동시에 처리 중이던 최대 수. */
  int maxFieldsInFlight() {
    return jira.maxInFlight();
  }

  // ---- 실행 ----

  JsonNode readFields() {
    return client.json(config, jira.token(), FIELDS_PAGE, TIMEOUT);
  }

  JsonNode read(String pathAndQuery) {
    return client.json(config, jira.token(), pathAndQuery, TIMEOUT);
  }

  /** 필드 목록을 부를 때 난 실패를 TS classifyJiraError와 같은 모양으로. 성공하면 given 준비 실패. */
  FailureView fieldsFailure() {
    try {
      readFields();
    } catch (RuntimeException e) {
      return FailureView.of(JiraErrors.classify(e));
    }
    throw new IllegalStateException("given 준비 실패: 필드 목록 요청이 실패해야 한다");
  }

  /** 이 경로를 부를 때 던진 예외(성공하면 given 준비 실패). */
  RuntimeException failureOf(String pathAndQuery) {
    try {
      read(pathAndQuery);
    } catch (RuntimeException e) {
      return e;
    }
    throw new IllegalStateException("given 준비 실패: 요청이 실패해야 한다");
  }

  /** 자격 증명 파일에 토큰을 쓰고 `file:` 참조를 제품 해석기로 풀어 대시보드 목록을 부른다. */
  JsonNode readDashboardsWithFileCredential(Path dir) throws IOException {
    Path tokenFile = dir.resolve("jira-token");
    Files.writeString(tokenFile, jira.token() + "\n");
    String token = SchemeCredentialResolver.standard(Map.of()).resolve("file:" + tokenFile);
    return client.json(config, token, DASHBOARDS_PAGE, TIMEOUT);
  }

  JiraApiClient.Attachment attachment(String id) {
    return client.attachment(config, jira.token(), id, TIMEOUT, ROOMY_LIMIT);
  }

  /** 데이터셋의 첫 필드 응답(Retry-After 1초의 요청 제한)을 소비한다 — 다른 실패면 given 준비 실패. */
  void givenFieldsRateLimitedOnce() {
    FailureView first = fieldsFailure();
    if (!first.kind().equals("rate_limited"))
      throw new IllegalStateException("given 준비 실패: 첫 필드 응답은 요청 제한이어야 한다 — " + first);
  }

  /** 필드 목록을 네 스레드에서 동시에 부르고 모두 받는다(하나라도 필드 배열이 아니면 given 준비 실패). */
  void readFieldsFromFourThreads() throws Exception {
    List<JsonNode> reads = readFieldsConcurrently();
    if (reads.size() != CONCURRENT_READS || !reads.stream().allMatch(JsonNode::isArray))
      throw new IllegalStateException("given 준비 실패: 네 요청이 모두 필드 배열을 받아야 한다 — " + reads);
  }

  private List<JsonNode> readFieldsConcurrently() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_READS);
    try {
      List<Future<JsonNode>> reads =
          IntStream.range(0, CONCURRENT_READS)
              .mapToObj(i -> pool.submit(this::readFields))
              .toList();
      List<JsonNode> out = new ArrayList<>();
      for (Future<JsonNode> read : reads)
        out.add(read.get(TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS));
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- 관찰 ----

  List<Recorded> requests() {
    return jira.requests();
  }

  void attachmentAnswers(String id, Response response) {
    jira.respond(ATTACHMENT + id, response);
  }

  String token() {
    return jira.token();
  }

  String expectedAuthorization() {
    return JiraFixtures.client().get("authorization").asString();
  }

  /** CollectionFailure를 TS 실패 객체와 같은 필드로(retryAfterMs는 없으면 null). */
  record FailureView(String kind, boolean retryable, String message, Long retryAfterMs) {
    static FailureView of(CallFailure f) {
      return new FailureView(
          f.kind().wire(),
          f.retryable(),
          f.message(),
          f.retryAfter().map(Duration::toMillis).orElse(null));
    }

    static FailureView expected(JsonNode f) {
      JsonNode ms = f.get("retryAfterMs");
      return new FailureView(
          f.get("kind").asString(),
          f.get("retryable").asBoolean(),
          f.get("message").asString(),
          ms == null || ms.isNull() ? null : ms.asLong());
    }
  }

  @Override
  public void close() {
    jira.close();
  }
}
