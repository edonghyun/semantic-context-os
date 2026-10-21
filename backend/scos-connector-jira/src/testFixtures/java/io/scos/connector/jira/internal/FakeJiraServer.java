package io.scos.connector.jira.internal;

import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 가짜 Jira REST(합성 데이터셋 harness/fixtures/jira/jira-dataset.json). 공용 {@link FakeHttpServer} 위의 얇은
 * 층이다: 데이터셋 라우트(경로 + 디코드한 쿼리 → 차례 응답, 다 쓰면 마지막 반복)와 Jira 형식의 인증 거절(401)만 정한다. 경로는 맞는데 쿼리가 데이터셋에 없는
 * 요청은 404로 답하고 미등록으로 남긴다 — 잘못된 쿼리(JQL·페이지 위치)가 조용히 통과하지 않게. 실제 Jira로는 나가지 않는다.
 */
public final class FakeJiraServer implements AutoCloseable {
  private record Route(
      Map<String, String> params, List<Response> responses, AtomicInteger served) {}

  private final JsonNode dataset;
  private final FakeHttpServer http;
  private final Map<String, List<Route>> routes = new LinkedHashMap<>();
  private final Map<String, Function<Recorded, Response>> overrides = new ConcurrentHashMap<>();

  /** 데이터셋 응답을 고쳐 답하는 경로(override가 없을 때). */
  private final Map<String, UnaryOperator<Response>> filters = new ConcurrentHashMap<>();

  private final List<String> unmatchedQueries = new CopyOnWriteArrayList<>();

  static final String ISSUE_SEARCH = "/rest/api/3/search/jql";

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 동시에 n개가 모이기를 기다리는 상한 — 하나씩 부르는 구현은 이만큼 늦은 뒤 진행한다. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(2);

  public FakeJiraServer(JsonNode dataset) throws IOException {
    this.dataset = dataset;
    this.http = new FakeHttpServer();
    http.requireHeader(
        "Authorization",
        basicAuthorization(),
        new Response(401, "{\"errorMessages\":[\"unauthorized\"]}", Map.of()));
    for (JsonNode r : dataset.get("routes")) {
      Map<String, String> params = new HashMap<>();
      r.get("params").properties().forEach(e -> params.put(e.getKey(), e.getValue().asString()));
      List<Response> responses = new ArrayList<>();
      r.get("responses").forEach(reply -> responses.add(response(reply)));
      String path = r.get("path").asString();
      routes
          .computeIfAbsent(path, p -> new ArrayList<>())
          .add(new Route(Map.copyOf(params), responses, new AtomicInteger()));
    }
    routes.keySet().forEach(path -> http.on("GET", path, request -> answer(path, request)));
  }

  public String baseUrl() {
    return http.baseUrl();
  }

  String email() {
    return dataset.get("email").asString();
  }

  public String token() {
    return dataset.get("token").asString();
  }

  /** 데이터셋 자격 증명의 Basic 헤더 값. */
  String basicAuthorization() {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((email() + ":" + token()).getBytes(StandardCharsets.UTF_8));
  }

  /** 이 경로(쿼리 무관)에는 데이터셋 대신 이 응답으로 답한다 — 경로를 새로 등록하기도 한다. */
  void respond(String path, Response response) {
    respondWith(path, request -> response);
  }

  /** 이 경로(쿼리 무관)에는 요청마다 이 처리기로 답한다(동시성 각본 등). */
  void respondWith(String path, Function<Recorded, Response> handler) {
    overrides.put(path, handler);
    if (!routes.containsKey(path)) http.on("GET", path, request -> answer(path, request));
  }

  /** 이슈 검색 응답(데이터셋)에서 이 이슈를 뺀다. 그 밖의 요청은 데이터셋 그대로다. */
  void omitFromIssueSearch(String issueId) {
    filters.put(
        ISSUE_SEARCH,
        response -> {
          ObjectNode body = (ObjectNode) JSON.readTree(response.body());
          ArrayNode kept = JSON.createArrayNode();
          body.get("issues")
              .forEach(
                  i -> {
                    if (!i.path("id").asString("").equals(issueId)) kept.add(i);
                  });
          body.set("issues", kept);
          return new Response(
              response.status(), body.toString(), Map.of("Content-Type", "application/json"));
        });
  }

  void holdUntilInFlight(String pathPrefix, int n) {
    http.holdUntilInFlight(pathPrefix, n, WAIT_LIMIT);
  }

  /** holdUntilInFlight 이후 같은 접두의 요청이 동시에 처리 중이던 최대 수. */
  int maxInFlight() {
    return http.maxInFlight();
  }

  /** 이 경로 접두의 다음 n개 요청은 이 상태로 답한다(그 뒤는 데이터셋). */
  void failNext(String pathPrefix, int times, int status) {
    http.failNext(pathPrefix, times, new Response(status, "{}", Map.of()));
  }

  /** TS 장애 사례(expected-failures.json의 failure): 그 경로 접두의 요청은 이제부터 늘 그 상태·헤더로 답한다. */
  void failAlways(JsonNode failure) {
    Map<String, String> headers = new LinkedHashMap<>();
    JsonNode h = failure.get("headers");
    if (h != null) h.properties().forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
    failAlways(failure.get("pathPrefix").asString(), failure.get("status").asInt(), headers);
  }

  /** 이 경로 접두의 요청은 이제부터 늘 이 상태·헤더로 답한다(계속되는 장애 — 재시도 소진). */
  void failAlways(String pathPrefix, int status, Map<String, String> headers) {
    http.failNext(pathPrefix, Integer.MAX_VALUE, new Response(status, "{}", headers));
  }

  List<Recorded> requests() {
    return http.received();
  }

  void gateOn(String pathContains, CountDownLatch latch, Consumer<String> onArrive) {
    http.gateOn(pathContains, latch, onArrive);
  }

  void assertNoUnmatched() {
    http.assertNoUnmatched();
    if (!unmatchedQueries.isEmpty()) throw new AssertionError("데이터셋에 없는 쿼리: " + unmatchedQueries);
  }

  private Response answer(String path, Recorded request) {
    return answerNow(path, request);
  }

  private Response answerNow(String path, Recorded request) {
    Function<Recorded, Response> override = overrides.get(path);
    if (override != null) return override.apply(request);
    for (Route route : routes.getOrDefault(path, List.of())) {
      if (route.params().equals(request.params())) {
        int n = route.served().getAndIncrement();
        Response r = route.responses().get(Math.min(n, route.responses().size() - 1));
        return filters.getOrDefault(path, UnaryOperator.identity()).apply(r);
      }
    }
    unmatchedQueries.add(request.pathAndQuery());
    return FakeHttpServer.status(404, "no dataset route: " + request.pathAndQuery());
  }

  private static Response response(JsonNode reply) {
    int status = reply.get("status").asInt();
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.put("Content-Type", "application/json");
    if (reply.has("headers"))
      reply
          .get("headers")
          .properties()
          .forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
    if (reply.has("json")) return new Response(status, reply.get("json").toString(), headers);
    if (reply.has("text")) return new Response(status, reply.get("text").asString(), headers);
    return Response.binary(
        status, Base64.getDecoder().decode(reply.get("base64").asString()), headers);
  }

  /** 검사 없이 닫는다 — 가짜 자체를 시험하는 테스트(미등록을 일부러 만든다)만 쓴다. */
  void abandon() {
    http.abandon();
  }

  @Override
  public void close() {
    try {
      assertNoUnmatched();
    } finally {
      http.close();
    }
  }
}
