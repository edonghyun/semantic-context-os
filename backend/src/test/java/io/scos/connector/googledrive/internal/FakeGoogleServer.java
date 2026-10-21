package io.scos.connector.googledrive.internal;

import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 가짜 Google API(합성 데이터셋 harness/fixtures/google-drive/google-drive-dataset.json). 공용 {@link
 * FakeHttpServer} 위의 얇은 층이다: 다섯 origin의 데이터셋 라우트(경로 + 디코드한 쿼리 → 차례 응답, 다 쓰면 마지막 반복)를 한 주소에서 답하고,
 * OAuth 토큰 끝점(폼이 데이터셋과 같아야 200, 아니면 400)과 Bearer 인증 거절(401)만 정한다. 경로는 맞는데 쿼리가 데이터셋에 없는 요청은 404로 답하고
 * 미등록으로 남긴다. 실제 Google로는 나가지 않는다.
 */
public final class FakeGoogleServer implements AutoCloseable {
  /** 데이터 요청 상한 — 같은 페이지 토큰을 끝없이 다시 묻는 결함(변이)을 비재시도 실패(400)로 끝낸다. fixture run은 백 개 안쪽이다. */
  static final int MAX_REQUESTS = 2000;

  private static final Pattern AMPERSAND = Pattern.compile("&");

  private record Route(
      Map<String, String> params, List<Response> responses, AtomicInteger served) {}

  private final JsonNode dataset;
  private final FakeHttpServer http;
  private final Map<String, List<Route>> routes = new LinkedHashMap<>();
  private final Map<String, List<Route>> secondRunRoutes = new LinkedHashMap<>();
  private final Map<String, Response> overrides = new ConcurrentHashMap<>();
  private final Map<String, Response> mediaOverrides = new ConcurrentHashMap<>();
  private final List<String> unmatchedQueries = new CopyOnWriteArrayList<>();
  private volatile boolean secondRun;
  private volatile Recorded crashAt;
  private volatile Response tokenOverride;
  private final AtomicInteger answered = new AtomicInteger();
  private final int maxRequests;

  public FakeGoogleServer(JsonNode dataset) throws IOException {
    this(dataset, MAX_REQUESTS);
  }

  /** 데이터 요청 상한을 바꾼 서버 — 상한 자체를 싸게 시험하는 자체 테스트만 쓴다. */
  FakeGoogleServer(JsonNode dataset, int maxRequests) throws IOException {
    this.dataset = dataset;
    this.maxRequests = maxRequests;
    this.http = new FakeHttpServer();
    http.requireHeader(
        "Authorization",
        "Bearer " + accessToken(),
        new Response(401, "{\"error\":{\"code\":401}}", Map.of()));
    http.withoutHeaderCheck("POST", prefixed("oauth", tokenPath()));
    http.on("POST", prefixed("oauth", tokenPath()), this::token);
    load(dataset.get("routes"), routes);
    load(dataset.get("secondRunRoutes"), secondRunRoutes);
  }

  private void load(JsonNode list, Map<String, List<Route>> into) {
    for (JsonNode r : list) {
      Map<String, String> params = new HashMap<>();
      r.get("params").properties().forEach(e -> params.put(e.getKey(), e.getValue().asString()));
      List<Response> responses = new ArrayList<>();
      r.get("responses").forEach(reply -> responses.add(GoogleDriveFixtures.response(reply)));
      String path = prefixed(serviceOfOrigin(r.get("origin").asString()), r.get("path").asString());
      into.computeIfAbsent(path, p -> new ArrayList<>())
          .add(new Route(Map.copyOf(params), responses, new AtomicInteger()));
      register(path);
    }
  }

  /** 서비스 origin의 접두를 붙인 경로(가짜 서버가 실제로 받는 경로). */
  static String prefixed(String service, String path) {
    return GoogleDriveAssembly.ORIGIN_PREFIXES.get(service) + path;
  }

  /** 데이터셋 origin URL → 서비스 이름. */
  private String serviceOfOrigin(String origin) {
    for (var e : dataset.get("origins").properties())
      if (e.getValue().asString().equals(origin)) return e.getKey();
    throw new IllegalArgumentException("unknown origin: " + origin);
  }

  private void register(String path) {
    http.on("GET", path, request -> answer(path, request));
  }

  public String baseUrl() {
    return http.baseUrl();
  }

  String accessToken() {
    return dataset.get("oauth").get("accessToken").asString();
  }

  private String tokenPath() {
    return dataset.get("oauth").get("tokenPath").asString();
  }

  /** 두 번째 수집(변경 피드 run)의 라우트가 같은 요청의 데이터셋 라우트를 대신한다. */
  void useSecondRunRoutes() {
    secondRun = true;
  }

  /** 이 경로(쿼리 무관)의 원본 다운로드가 아닌 요청에는 이 응답으로 답한다 — 경로를 새로 등록하기도 한다. 토큰 경로면 토큰 끝점이 답한다. */
  void respond(String path, Response response) {
    String service = GoogleDriveAssembly.serviceOf(path);
    if (service.equals("oauth")) {
      tokenOverride = response;
      return;
    }
    String full = prefixed(service, path);
    overrides.put(full, response);
    register(full);
  }

  /** 이 경로의 원본 다운로드(alt=media) 요청에는 이 응답으로 답한다. */
  void respondMedia(String path, Response response) {
    String full = prefixed("drive", path);
    mediaOverrides.put(full, response);
    register(full);
  }

  /** 이 요청(경로 + 쿼리)은 401로 답한다 — 비재시도 실패로 run을 그 자리에서 끝내는 중단 모사. */
  void crashAt(String path, Map<String, String> params) {
    crashAt =
        new Recorded(
            "GET",
            prefixed(GoogleDriveAssembly.serviceOf(path), path),
            "",
            Map.of(),
            "",
            Map.copyOf(params));
  }

  /** 덮어쓰기(경로·원본 다운로드·토큰 끝점)를 모두 걷는다 — 장애가 지나간 뒤의 다음 수집은 데이터셋 응답을 받는다. */
  void clearOverrides() {
    overrides.clear();
    mediaOverrides.clear();
    tokenOverride = null;
  }

  /** 중단 모사를 걷는다(다음 프로세스는 정상 응답을 받는다). */
  void recover() {
    crashAt = null;
  }

  /** 경로+쿼리에 pathContains가 들어간 첫 요청을 latch가 열릴 때까지 멈춘다(kill 지점). */
  void gateOn(String pathContains, CountDownLatch latch, Consumer<String> onArrive) {
    http.gateOn(pathContains, latch, onArrive);
  }

  /** 오버라이드 묶음(TS 사례의 overrides: 경로마다 응답, media면 원본 다운로드만). */
  void respondAll(JsonNode overridesJson) {
    for (JsonNode o : overridesJson) {
      Response response = GoogleDriveFixtures.response(o.get("reply"));
      if (o.path("media").asBoolean(false)) respondMedia(o.get("path").asString(), response);
      else respond(o.get("path").asString(), response);
    }
  }

  List<Recorded> requests() {
    return http.received();
  }

  void assertNoUnmatched() {
    http.assertNoUnmatched();
    if (!unmatchedQueries.isEmpty()) throw new AssertionError("데이터셋에 없는 쿼리: " + unmatchedQueries);
  }

  private Response token(Recorded request) {
    if (tokenOverride != null) return tokenOverride;
    String contentType = request.header("Content-Type");
    if (!form(request.body()).equals(expectedForm())
        || contentType == null
        || !contentType.startsWith("application/x-www-form-urlencoded"))
      return new Response(400, "{\"error\":\"invalid_grant\"}", Map.of());
    return new Response(
        200,
        dataset.get("oauth").get("response").toString(),
        Map.of("Content-Type", "application/json"));
  }

  private Map<String, String> expectedForm() {
    Map<String, String> form = new HashMap<>();
    dataset
        .get("oauth")
        .get("form")
        .properties()
        .forEach(e -> form.put(e.getKey(), e.getValue().asString()));
    return form;
  }

  private static Map<String, String> form(String body) {
    Map<String, String> out = new HashMap<>();
    for (String part : AMPERSAND.split(body, -1)) {
      int eq = part.indexOf('=');
      if (eq < 0) continue;
      out.put(
          URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private Response answer(String path, Recorded request) {
    if (answered.incrementAndGet() > maxRequests)
      return FakeHttpServer.status(400, "request limit exceeded: " + request.pathAndQuery());
    Recorded crash = crashAt;
    if (crash != null && crash.path().equals(path) && crash.params().equals(request.params()))
      return new Response(401, "{\"error\":{\"code\":401}}", Map.of());
    boolean media = "media".equals(request.params().get("alt"));
    Response override = (media ? mediaOverrides : overrides).get(path);
    if (override != null) return override;
    if (secondRun) {
      Response later = matching(secondRunRoutes, path, request);
      if (later != null) return later;
    }
    Response response = matching(routes, path, request);
    if (response != null) return response;
    unmatchedQueries.add(request.pathAndQuery());
    return FakeHttpServer.status(404, "no dataset route: " + request.pathAndQuery());
  }

  private static Response matching(Map<String, List<Route>> table, String path, Recorded request) {
    for (Route route : table.getOrDefault(path, List.of())) {
      if (route.params().equals(request.params())) {
        int n = route.served().getAndIncrement();
        return route.responses().get(Math.min(n, route.responses().size() - 1));
      }
    }
    return null;
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
