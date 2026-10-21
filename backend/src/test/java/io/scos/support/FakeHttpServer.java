package io.scos.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 공급자 가짜 HTTP의 공용 기반(TESTING §4·§3.6): 등록된 "메서드 경로"만 응답한다. 실제 네트워크로는 나가지 않는다. 요청 하나는 이 순서로 처리한다.
 *
 * <ol>
 *   <li>기록 — 모든 요청({@link #received()}, 헤더 포함).
 *   <li>메서드·경로 — 미등록이면 404(같은 경로에 다른 메서드가 있으면 405)로 답하고 미등록으로 기록한다. {@link #assertNoUnmatched()}를
 *       테스트 종료 시 부른다. 잘못된 요청이 인증 실패·장애 각본 응답에 가려지지 않게 맨 먼저 본다.
 *   <li>인증 — {@link #requireHeader}의 헤더가 다르면 거절 응답. 라우트별로 걸거나({@link #requireHeader(String, String,
 *       String, String, Response)}) 뺄 수 있다({@link #withoutHeaderCheck} — 토큰 끝점).
 *   <li>장애 각본 — {@link #failNext}의 경로 접두에 남은 횟수가 있으면 그 응답.
 *   <li>게이트 — {@link #gateOn}의 첫 요청은 래치가 열릴 때까지 멈춘다. 요청마다 스레드라 다른 요청은 그대로 응답한다.
 *   <li>등록된 응답.
 * </ol>
 *
 * 라우트 처리기가 던지면 500으로 답하고 기록한다. {@link #close()}는 미등록 요청·처리기 실패가 있으면 실패한다 — 검사를 부르는 것을 잊어도 잘못된 요청이
 * 조용히 지나가지 않는다. 커넥터별 가짜(`FakeSlackServer` 등)는 이 위에 라우트 응답과 공급자 형식의 고장 몸체만 얹는다.
 */
public final class FakeHttpServer implements AutoCloseable {
  /** 받은 요청. 헤더 이름은 소문자, query는 디코드한 원문, params는 디코드한 쿼리 파라미터(같은 이름은 마지막 값). */
  public record Recorded(
      String method,
      String path,
      String query,
      Map<String, String> headers,
      String body,
      Map<String, String> params) {
    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** 경로 + "?" + query(쿼리가 없으면 경로만). */
    public String pathAndQuery() {
      return query.isEmpty() ? path : path + "?" + query;
    }
  }

  /** 응답 하나. 본문은 바이트다 — 문자열 본문은 UTF-8로 넣는다. */
  public static final class Response {
    private final int status;
    private final byte[] bytes;
    private final Map<String, String> headers;

    public Response(int status, String body, Map<String, String> headers) {
      this(status, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private Response(int status, byte[] bytes, Map<String, String> headers) {
      this.status = status;
      this.bytes = bytes.clone();
      this.headers = Map.copyOf(headers);
    }

    public static Response binary(int status, byte[] bytes, Map<String, String> headers) {
      return new Response(status, bytes, headers);
    }

    public int status() {
      return status;
    }

    public byte[] bytes() {
      return bytes.clone();
    }

    /** 본문을 UTF-8 문자열로. */
    public String body() {
      return new String(bytes, StandardCharsets.UTF_8);
    }

    public Map<String, String> headers() {
      return headers;
    }
  }

  public static Response ok(String body) {
    return new Response(200, body, Map.of());
  }

  public static Response status(int status, String body) {
    return new Response(status, body, Map.of());
  }

  /** 429 + Retry-After(초). */
  public static Response rateLimited(long retryAfterSeconds) {
    return new Response(429, "{}", Map.of("Retry-After", Long.toString(retryAfterSeconds)));
  }

  /** 요청마다 차례로 응답하고, 다 쓰면 마지막 응답을 반복한다(재시도 각본: 실패 n번 뒤 성공). */
  public static Function<Recorded, Response> inOrder(Response... responses) {
    AtomicInteger next = new AtomicInteger();
    return req -> responses[Math.min(next.getAndIncrement(), responses.length - 1)];
  }

  private record RequiredHeader(String name, String value, Response rejected) {}

  private record Fault(AtomicInteger remaining, Response response) {}

  private record Gate(String pathContains, CountDownLatch latch, Consumer<String> onArrive) {}

  private static final Pattern AMPERSAND = Pattern.compile("&");

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, Function<Recorded, Response>> routes = new ConcurrentHashMap<>();
  private final List<Recorded> received = new CopyOnWriteArrayList<>();
  private final List<String> unmatched = new CopyOnWriteArrayList<>();
  private final Map<String, Fault> faults = new LinkedHashMap<>();
  private final List<String> handlerFailures = new CopyOnWriteArrayList<>();
  private final Map<String, Optional<RequiredHeader>> routeHeaders = new ConcurrentHashMap<>();
  private volatile RequiredHeader requiredHeader;
  private final AtomicReference<Gate> gate = new AtomicReference<>();
  private volatile InFlightHold inFlightHold;

  public FakeHttpServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    // 게이트에 걸린 요청이 다른 요청을 막지 않도록 요청마다 스레드(기본 executor는 단일 스레드)
    server.setExecutor(executor);
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** 메서드 + 경로(쿼리 제외)에 응답을 등록한다. 같은 키는 덮어쓴다. */
  public void on(String method, String path, Function<Recorded, Response> handler) {
    routes.put(method + " " + path, handler);
  }

  /** 등록된 요청의 이 헤더가 value가 아니면(없어도) rejected로 답한다 — 라우트별 설정이 없는 모든 라우트. */
  public void requireHeader(String name, String value, Response rejected) {
    requiredHeader = new RequiredHeader(name, value, rejected);
  }

  /** 이 라우트만 이 헤더를 요구한다(기본 검사 대신). */
  public void requireHeader(
      String method, String path, String name, String value, Response rejected) {
    routeHeaders.put(method + " " + path, Optional.of(new RequiredHeader(name, value, rejected)));
  }

  /** 이 라우트는 헤더를 검사하지 않는다 — 인증 전에 부르는 토큰 끝점 등. */
  public void withoutHeaderCheck(String method, String path) {
    routeHeaders.put(method + " " + path, Optional.empty());
  }

  /** 경로 접두(예: "/api/conversations.history")의 다음 count번 요청에 response로 답한다. 먼저 건 각본부터 본다. */
  public synchronized void failNext(String pathPrefix, int count, Response response) {
    faults.put(pathPrefix, new Fault(new AtomicInteger(count), response));
  }

  /** 경로+쿼리에 pathContains가 들어간 첫 요청을 latch가 열릴 때까지 멈춘다. 멈추기 전에 onArrive(경로+쿼리)를 부른다. */
  public void gateOn(String pathContains, CountDownLatch latch, Consumer<String> onArrive) {
    gate.set(new Gate(pathContains, latch, onArrive));
  }

  /**
   * 동시 수 측정(공급자 형식과 무관한 기계 장치 — 커넥터의 "동시 n개까지" 계약용): 경로 접두가 맞는 요청은 n개가 함께 처리 중이 될 때까지(상한 guard까지)
   * 답하지 않는다. 모이지 않으면 상한 뒤 그대로 진행하고, 동시에 처리 중이던 최대 수는 {@link #maxInFlight()}가 알려 준다.
   */
  public void holdUntilInFlight(String pathPrefix, int n, Duration guard) {
    inFlightHold = new InFlightHold(pathPrefix, n, guard);
  }

  /** holdUntilInFlight 이후 같은 접두의 요청이 동시에 처리 중이던 최대 수(걸지 않았으면 0). */
  public int maxInFlight() {
    InFlightHold h = inFlightHold;
    return h == null ? 0 : h.max.get();
  }

  private static final class InFlightHold {
    final String pathPrefix;
    final CountDownLatch arrivals;
    final Duration guard;
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger max = new AtomicInteger();

    InFlightHold(String pathPrefix, int n, Duration guard) {
      this.pathPrefix = pathPrefix;
      this.arrivals = new CountDownLatch(n);
      this.guard = guard;
    }

    void arrive() {
      max.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
      arrivals.countDown();
      try {
        // 모이지 않으면 상한 뒤 그대로 진행한다 — 동시 수는 max로 드러난다
        var unused = arrivals.await(guard.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    void leave() {
      inFlight.decrementAndGet();
    }
  }

  /** 걸어 둔 게이트를 걷는다(이미 붙잡힌 요청은 그 래치로 풀린다). */
  public void clearGate() {
    gate.set(null);
  }

  public List<Recorded> received() {
    return List.copyOf(received);
  }

  /** 미등록 요청과 라우트 처리기 실패가 없어야 한다. {@link #close()}가 부른다. */
  public void assertNoUnmatched() {
    assertThat(unmatched).as("미등록 요청").isEmpty();
    assertThat(handlerFailures).as("라우트 처리기 실패").isEmpty();
  }

  private void handle(HttpExchange ex) throws IOException {
    Recorded recorded = record(ex);
    received.add(recorded);
    respond(ex, answer(recorded));
  }

  private Response answer(Recorded recorded) {
    Function<Recorded, Response> route = routes.get(recorded.method() + " " + recorded.path());
    if (route == null) {
      unmatched.add(recorded.method() + " " + recorded.path());
      return pathHasOtherMethod(recorded.path())
          ? status(405, "method not allowed: " + recorded.method() + " " + recorded.path())
          : status(404, "no route: " + recorded.method() + " " + recorded.path());
    }
    RequiredHeader required =
        routeHeaders
            .getOrDefault(
                recorded.method() + " " + recorded.path(), Optional.ofNullable(requiredHeader))
            .orElse(null);
    if (required != null && !required.value().equals(recorded.header(required.name()))) {
      return required.rejected();
    }
    Response fault = takeFault(recorded.path());
    if (fault != null) return fault;
    holdAtGate(recorded.pathAndQuery());
    InFlightHold h = inFlightHold;
    boolean held = h != null && recorded.path().startsWith(h.pathPrefix);
    if (held) h.arrive();
    try {
      return route.apply(recorded);
    } catch (RuntimeException e) {
      // 처리기가 던지면 연결이 끊겨 클라이언트에는 재시도할 전송 오류로 보인다 — 500으로 답하고 검사에서 실패시킨다
      handlerFailures.add(recorded.method() + " " + recorded.path() + ": " + e);
      return status(500, "route handler failed");
    } finally {
      if (held) h.leave();
    }
  }

  private boolean pathHasOtherMethod(String path) {
    return routes.keySet().stream().anyMatch(key -> key.endsWith(" " + path));
  }

  private synchronized Response takeFault(String path) {
    for (var e : faults.entrySet()) {
      if (path.startsWith(e.getKey()) && e.getValue().remaining().getAndDecrement() > 0) {
        return e.getValue().response();
      }
    }
    return null;
  }

  /** 경로가 맞는 첫 요청 하나만 붙잡는다 — 동시 요청 둘이 함께 붙잡히지 않게 게이트를 원자적으로 걷는다. */
  private void holdAtGate(String pathAndQuery) {
    Gate g = gate.get();
    if (g == null || !pathAndQuery.contains(g.pathContains()) || !gate.compareAndSet(g, null))
      return;
    g.onArrive().accept(pathAndQuery);
    try {
      g.latch().await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Recorded record(HttpExchange ex) throws IOException {
    Map<String, String> headers = new TreeMap<>();
    ex.getRequestHeaders()
        .forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
    URI uri = ex.getRequestURI();
    return new Recorded(
        ex.getRequestMethod(),
        uri.getPath(),
        uri.getQuery() == null ? "" : uri.getQuery(),
        headers,
        new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
        params(uri.getRawQuery()));
  }

  private static Map<String, String> params(String rawQuery) {
    Map<String, String> out = new LinkedHashMap<>();
    if (rawQuery == null) return out;
    for (String part : AMPERSAND.split(rawQuery, -1)) {
      int eq = part.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
      out.put(k, v);
    }
    return out;
  }

  private static void respond(HttpExchange ex, Response response) throws IOException {
    byte[] out = response.bytes();
    response.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
    ex.sendResponseHeaders(response.status(), out.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(out);
    }
  }

  /** 미등록 요청·처리기 실패를 검사하고 닫는다(검사가 실패해도 닫는다). */
  @Override
  public void close() {
    try {
      assertNoUnmatched();
    } finally {
      abandon();
    }
  }

  /** 검사 없이 닫는다 — 가짜 자체를 시험하는 테스트(미등록·실패를 일부러 만든다)만 쓴다. */
  public void abandon() {
    server.stop(0);
    executor.shutdownNow();
  }
}
