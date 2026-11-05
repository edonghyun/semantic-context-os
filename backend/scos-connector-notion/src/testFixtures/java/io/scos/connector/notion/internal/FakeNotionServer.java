package io.scos.connector.notion.internal;

import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * 가짜 Notion API(합성 데이터셋 harness/fixtures/notion/notion-dataset.json). 공용 {@link FakeHttpServer} 위의
 * 얇은 층이다: 데이터셋 라우트(메서드·경로·디코드한 쿼리·POST 본문 JSON → 차례 응답, 다 쓰면 마지막 반복)와 Notion 형식의 인증 거절(401
 * unauthorized)만 정한다. 응답 본문은 데이터셋의 원문 그대로다. 미등록 쿼리·본문, 다른 Notion-Version, JSON이 아닌 Content-Type의
 * POST는 실패로 남긴다. 실제 Notion으로는 나가지 않는다.
 */
public final class FakeNotionServer implements AutoCloseable {
  /** 데이터셋 라우트 하나. body는 POST 본문 JSON(GET은 null) — 키 순서와 무관하게 비교한다. */
  private record Route(
      Map<String, String> params, JsonNode body, List<Response> responses, AtomicInteger served) {
    boolean matches(Recorded request) {
      if (!params.equals(request.params())) return false;
      var requested = bodyOf(request);
      return Objects.equals(body, requested);
    }

    Response next() {
      return responses.get(Math.min(served.getAndIncrement(), responses.size() - 1));
    }
  }

  /** 장애 각본 하나. params·body가 없으면 그 면은 가리지 않는다. responses가 비면 답하지 않는다(hang). */
  private record Fault(
      Optional<Map<String, String>> params, Optional<JsonNode> body, Route replies) {
    boolean matches(Recorded request) {
      return params.map(p -> p.equals(request.params())).orElse(true)
          && body.map(b -> b.equals(bodyOf(request))).orElse(true);
    }
  }

  /** 데이터셋 갈래: 처음 / afterEdit 적용 / afterShare·afterEdit 적용. */
  enum Phase {
    INITIAL,
    EDITED,
    SHARED
  }

  /**
   * 한 서버가 답하는 요청 상한. 넘으면 비재시도 오류(validation_error)로 답하고 실패로 남긴다 — 끝나지 않는 순회(변이·결함)가 테스트 시간 상한이 아니라
   * 단언으로 드러나게. 데이터셋에서 가장 긴 run(discoverSharedPages 사이클)이 30요청이다.
   */
  static final int REQUEST_BUDGET = 120;

  private final int requestBudget;

  static final String UNAUTHORIZED =
      "{\"object\":\"error\",\"status\":401,\"code\":\"unauthorized\",\"message\":\"synthetic\"}";

  private final JsonNode dataset;
  private final FakeHttpServer http;
  private final Map<String, List<Route>> base = new LinkedHashMap<>();
  private final Map<String, List<Route>> edited = new LinkedHashMap<>();
  private final Map<String, List<Route>> shared = new LinkedHashMap<>();
  private final Map<String, List<Fault>> faults = new ConcurrentHashMap<>();
  private final Map<String, Response> overrides = new ConcurrentHashMap<>();
  private final Map<String, Function<Optional<String>, Response>> scripts =
      new ConcurrentHashMap<>();
  private final List<String> failures = new CopyOnWriteArrayList<>();
  private final CountDownLatch released = new CountDownLatch(1);
  private final AtomicInteger answered = new AtomicInteger();
  private volatile Phase phase = Phase.INITIAL;
  private volatile JsonNode crashAt;
  private volatile JsonNode holdAt;
  private volatile Runnable onHold;
  private volatile Runnable onHang = () -> {};

  public FakeNotionServer(JsonNode dataset) throws IOException {
    this(dataset, REQUEST_BUDGET);
  }

  /** 요청 상한을 바꾼 서버 — 상한 자체를 싸게 시험하는 자체 테스트만 쓴다. */
  FakeNotionServer(JsonNode dataset, int requestBudget) throws IOException {
    this.dataset = dataset;
    this.requestBudget = requestBudget;
    this.http = new FakeHttpServer();
    http.requireHeader(
        "Authorization", "Bearer " + token(), new Response(401, UNAUTHORIZED, Map.of()));
    load(dataset.get("routes"), base);
    load(dataset.get("afterEdit"), edited);
    load(dataset.get("afterShare"), shared);
    base.keySet().forEach(this::register);
  }

  public String baseUrl() {
    return http.baseUrl();
  }

  public String token() {
    return dataset.get("token").asString();
  }

  String apiVersion() {
    return dataset.get("apiVersion").asString();
  }

  /** 데이터셋 갈래를 바꾼다(다음 요청부터). */
  void phase(Phase next) {
    this.phase = next;
  }

  /** 이 요청(fixture의 기록 모양: method·path·params·body 원문)은 인증 실패로 답한다 — run을 그 자리에서 끝내는 중단 모사. */
  void crashAt(JsonNode request) {
    this.crashAt = request;
  }

  /**
   * 이 요청(fixture의 기록 모양)이 오면 onArrive를 부르고 서버가 닫힐 때까지 답하지 않는다 — 그 페이지를 내기 전에 프로세스를 죽이는 kill 각본(POST
   * 본문까지 맞춘다 — 같은 경로의 첫 페이지는 지나간다).
   */
  void holdAt(JsonNode request, Runnable onArrive) {
    this.onHold = onArrive;
    this.holdAt = request;
  }

  /** 답하지 않기 시작할 때(hang 장애 각본) 부른다 — 요청이 걸린 동안의 취소·인터럽트 각본. */
  void onHang(Runnable onArrive) {
    this.onHang = onArrive;
  }

  /** 중단 각본을 걷는다(재개 run). */
  void clearCrash() {
    this.crashAt = null;
  }

  /**
   * 장애 각본 하나(expected-failures.json의 faults 항목): 맞는 요청에 데이터셋 대신 responses를 차례로 답하거나, hang이면 서버가 닫힐
   * 때까지 답하지 않는다(시도 timeout).
   */
  void fault(JsonNode fault) {
    String key = fault.get("method").asString() + " " + fault.get("path").asString();
    Optional<Map<String, String>> params =
        Optional.ofNullable(fault.get("params")).map(FakeNotionServer::params);
    List<Response> responses = new ArrayList<>();
    if (fault.has("responses")) fault.get("responses").forEach(r -> responses.add(response(r)));
    faults
        .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
        .add(
            new Fault(
                params,
                Optional.ofNullable(fault.get("body")),
                new Route(Map.of(), null, responses, new AtomicInteger())));
    register(key);
  }

  /**
   * 커서가 두 값 사이를 도는 목록(A → B → A): 이 목록 경로는 요청 커서(GET은 start_cursor 쿼리, POST는 본문)에 따라 빈 결과와 다음 커서로
   * 답한다 — 없음 → A, A → B, B → A(한 번), 그 뒤 B → 끝. 가드가 없는 순회도 한 바퀴 뒤 끝나 테스트가 단언으로 실패한다.
   */
  void cycleCursor(String method, String path, String type) {
    script(method, path, new CursorCycle(type, false)::answer);
    register(method + " " + path);
  }

  /**
   * 요청한 커서를 다음 커서로 다시 주는 목록(A → A): 없음 → A, A → A(한 번), 그 뒤 A → 끝. 가드가 없는 순회도 끝나 테스트가 단언으로 실패한다.
   */
  void repeatCursor(String method, String path, String type) {
    script(method, path, new CursorCycle(type, true)::answer);
    register(method + " " + path);
  }

  /**
   * 이 목록 경로는 요청 커서(GET은 start_cursor 쿼리, POST는 본문, 첫 페이지는 없음)에 따라 이 각본으로 답한다 — 같은 커서가 두 번 오면 각본이 몇
   * 번째인지 센다. 데이터셋보다 앞선다.
   */
  void script(String method, String path, Function<Optional<String>, Response> answer) {
    scripts.put(method + " " + path, answer);
    register(method + " " + path);
  }

  private static final class CursorCycle {
    static final String A = "cycle-a";
    static final String B = "cycle-b";
    private final String type;
    private final boolean repeat;
    private final AtomicInteger returned = new AtomicInteger();

    CursorCycle(String type, boolean repeat) {
      this.type = type;
      this.repeat = repeat;
    }

    Response answer(Optional<String> cursor) {
      Optional<String> next =
          cursor.isEmpty()
              ? Optional.of(A)
              : !repeat && cursor.get().equals(A)
                  ? Optional.of(B)
                  : returned.getAndIncrement() == 0 ? Optional.of(A) : Optional.empty();
      String nextJson = next.map(c -> "\"" + c + "\"").orElse("null");
      return new Response(
          200,
          "{\"object\":\"list\",\"results\":[],\"next_cursor\":"
              + nextJson
              + ",\"has_more\":"
              + next.isPresent()
              + ",\"type\":\""
              + type
              + "\",\""
              + type
              + "\":{}}",
          Map.of());
    }
  }

  private static Optional<String> requestedCursor(Recorded request) {
    if (request.method().equals("GET"))
      return Optional.ofNullable(request.params().get("start_cursor"));
    JsonNode body = bodyOf(request);
    JsonNode cursor = body == null ? null : body.get("start_cursor");
    return cursor == null || !cursor.isString() ? Optional.empty() : Optional.of(cursor.asString());
  }

  /** 이 메서드·경로(쿼리·본문 무관)에는 데이터셋 대신 이 응답으로 답한다 — 경로를 새로 등록하기도 한다. */
  void respond(String method, String path, Response response) {
    overrides.put(method + " " + path, response);
    register(method + " " + path);
  }

  public List<Recorded> requests() {
    return http.received();
  }

  public void assertNoUnmatched() {
    http.assertNoUnmatched();
    if (!failures.isEmpty()) throw new AssertionError("데이터셋 밖 요청: " + failures);
  }

  private void register(String key) {
    int space = key.indexOf(' ');
    http.on(key.substring(0, space), key.substring(space + 1), request -> answer(key, request));
  }

  private Response answer(String key, Recorded request) {
    if (answered.incrementAndGet() > requestBudget) {
      failures.add("요청 상한 초과: " + key);
      return new Response(
          400, "{\"object\":\"error\",\"status\":400,\"code\":\"validation_error\"}", Map.of());
    }
    if (!apiVersion().equals(request.header("Notion-Version"))) {
      failures.add("Notion-Version 다름: " + request.header("Notion-Version"));
      return new Response(401, UNAUTHORIZED, Map.of());
    }
    if (request.method().equals("POST")
        && !"application/json".equals(request.header("Content-Type"))) {
      failures.add("POST Content-Type이 JSON이 아님: " + request.header("Content-Type"));
      return new Response(400, "{\"object\":\"error\",\"code\":\"invalid_json\"}", Map.of());
    }
    if (crashAt != null && same(crashAt, request)) return new Response(401, UNAUTHORIZED, Map.of());
    if (holdAt != null && same(holdAt, request)) {
      onHold.run();
      return hang();
    }
    for (Fault fault : faults.getOrDefault(key, List.of()))
      if (fault.matches(request)) {
        if (!fault.replies().responses().isEmpty()) return fault.replies().next();
        onHang.run();
        return hang();
      }
    Function<Optional<String>, Response> scripted = scripts.get(key);
    if (scripted != null) return scripted.apply(requestedCursor(request));
    Response override = overrides.get(key);
    if (override != null) return override;
    for (Map<String, List<Route>> routes : activeRoutes())
      for (Route route : routes.getOrDefault(key, List.of()))
        if (route.matches(request)) return route.next();
    failures.add(key + "?" + request.query() + " " + request.body());
    return FakeHttpServer.status(404, "no dataset route: " + key);
  }

  /** fixture의 기록 모양(method·path·params·body 원문)과 같은 요청인가. */
  private static boolean same(JsonNode expected, Recorded request) {
    return expected.get("method").asString().equals(request.method())
        && expected.get("path").asString().equals(request.path())
        && params(expected.get("params")).equals(request.params())
        && Objects.equals(
            expected.path("body").asString().isEmpty()
                ? null
                : NotionFixtures.JSON.readTree(expected.path("body").asString()),
            bodyOf(request));
  }

  private List<Map<String, List<Route>>> activeRoutes() {
    return switch (phase) {
      case INITIAL -> List.of(base);
      case EDITED -> List.of(edited, base);
      case SHARED -> List.of(shared, edited, base);
    };
  }

  /** 서버가 닫힐 때까지 답하지 않는다 — 클라이언트의 시도 timeout이 끊는다. */
  private Response hang() {
    try {
      released.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return FakeHttpServer.status(503, "released");
  }

  private static void load(JsonNode routes, Map<String, List<Route>> into) {
    for (JsonNode r : routes) {
      List<Response> responses = new ArrayList<>();
      r.get("responses").forEach(reply -> responses.add(response(reply)));
      JsonNode body = r.get("body").isNull() ? null : r.get("body");
      into.computeIfAbsent(
              r.get("method").asString() + " " + r.get("path").asString(), k -> new ArrayList<>())
          .add(new Route(params(r.get("params")), body, responses, new AtomicInteger()));
    }
  }

  private static Map<String, String> params(JsonNode params) {
    Map<String, String> out = new LinkedHashMap<>();
    params.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
    return Map.copyOf(out);
  }

  private static Response response(JsonNode reply) {
    Map<String, String> headers = new LinkedHashMap<>();
    reply
        .get("headers")
        .properties()
        .forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
    return new Response(reply.get("status").asInt(), reply.get("text").asString(), headers);
  }

  private static JsonNode bodyOf(Recorded request) {
    return request.body().isEmpty() ? null : NotionFixtures.JSON.readTree(request.body());
  }

  /** 검사 없이 닫는다 — 가짜 자체를 시험하는 테스트(미등록을 일부러 만든다)만 쓴다. */
  void abandon() {
    released.countDown();
    http.abandon();
  }

  @Override
  public void close() {
    released.countDown();
    try {
      assertNoUnmatched();
    } finally {
      http.close();
    }
  }
}
