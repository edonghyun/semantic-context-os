package io.scos.connector.googledrive.internal;

import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Google 세션 테스트의 연결: 토큰 끝점과 데이터 경로만 있는 가짜 Google, 밀리초 시계(0에서 시작 — TS 표의 at), 데이터셋 자격 증명. 토큰 요청과 데이터
 * 요청을 받은 시각·Authorization과 함께 기록한다. 본문은 "언제 어떤 응답에 부르면 무엇이 나오는가"만 쓴다.
 */
final class GoogleSessionScenario implements AutoCloseable {
  static final Duration TIMEOUT = Duration.ofSeconds(10);
  static final String TOKEN = "/token";
  static final String ABOUT = "/drive/v3/about";

  /** 다운로드 경로(alt=media)의 파일과 그 상한. */
  static final String DOWNLOADED_FILE = "pdf-1";

  /** 리다이렉트 Location이 가리키는 다른 호스트의 경로 — 요청이 여기 오면 Bearer·refresh token이 샌 것이다. */
  static final String ELSEWHERE = "/collect";

  private static final List<String> DATA_PATHS =
      List.of(
          ABOUT, "/v1/presentations/x", "/drive/v3/files/x", "/drive/v3/files/" + DOWNLOADED_FILE);

  /** 받은 요청 하나: 토큰 요청이면 path가 "/token"이고 authorization은 없다. */
  record Event(String path, long at, String authorization) {
    static Event expected(JsonNode e) {
      return e.has("token")
          ? new Event(TOKEN, e.get("at").asLong(), null)
          : new Event(
              e.get("request").asString(), e.get("at").asLong(), e.get("authorization").asString());
    }
  }

  /** 실패의 비교 값(TS CallFailure). */
  record FailureView(String kind, boolean retryable, String message, Long retryAfterMs) {
    static FailureView of(CallFailure f) {
      return new FailureView(
          f.kind().wire(),
          f.retryable(),
          f.message(),
          f.retryAfter().map(Duration::toMillis).orElse(null));
    }

    static FailureView expected(JsonNode f) {
      return new FailureView(
          f.get("kind").asString(),
          f.get("retryable").asBoolean(),
          f.get("message").asString(),
          f.get("retryAfterMs").isNull() ? null : f.get("retryAfterMs").asLong());
    }
  }

  /** 호출 하나의 결과: 성공이면 failure가 null. */
  record Result(boolean ok, FailureView failure) {
    static Result expected(JsonNode r) {
      return r.get("ok").asBoolean()
          ? new Result(true, null)
          : new Result(false, FailureView.expected(r.get("failure")));
    }
  }

  private final FakeHttpServer http;
  private final FakeHttpServer other;
  private final MutableClock clock = new MutableClock(Instant.EPOCH);
  private final List<Event> events = new CopyOnWriteArrayList<>();
  private final GoogleDriveApiClient client;
  private volatile Response dataReply = FakeHttpServer.ok("{\"ok\":true}");
  private volatile Function<Recorded, Response> tokenReplies =
      request -> FakeHttpServer.ok("{\"access_token\":\"access-1\",\"expires_in\":3600}");

  GoogleSessionScenario() {
    try {
      http = new FakeHttpServer();
      other = new FakeHttpServer();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    http.on(
        "POST",
        FakeGoogleServer.prefixed("oauth", TOKEN),
        request -> {
          events.add(new Event(TOKEN, clock.millis(), null));
          return tokenReplies.apply(request);
        });
    for (String path : DATA_PATHS)
      http.on(
          "GET",
          FakeGoogleServer.prefixed(GoogleDriveAssembly.serviceOf(path), path),
          request -> {
            events.add(new Event(path, clock.millis(), request.header("Authorization")));
            return dataReply;
          });
    other.on("GET", ELSEWHERE, request -> FakeHttpServer.ok("{}"));
    other.on(
        "POST",
        ELSEWHERE,
        request -> FakeHttpServer.ok("{\"access_token\":\"x\",\"expires_in\":3600}"));
    client = GoogleDriveAssembly.client(http.baseUrl(), clock);
  }

  // ---- 준비 ----

  /** 토큰 끝점이 차례로 이 응답을 하고, 다 쓰면 마지막을 반복한다. */
  void tokenAnswers(List<Response> replies) {
    tokenReplies = FakeHttpServer.inOrder(replies.toArray(Response[]::new));
  }

  /** 데이터 경로가 이 응답을 한다. */
  void dataAnswers(Response reply) {
    dataReply = reply;
  }

  /** 이 상태의 리다이렉트 응답(Location은 다른 호스트 — 두 번째 가짜 서버). */
  Response redirect(int status) {
    return new Response(status, "", Map.of("Location", other.baseUrl() + ELSEWHERE));
  }

  // ---- 실행 ----

  /** 데이터셋 자격 증명의 세션. */
  GoogleDriveSession session() {
    return client.session(GoogleDriveFixtures.credentialJson());
  }

  GoogleDriveSession session(String credentialJson) {
    return client.session(credentialJson);
  }

  /** 시계를 at(밀리초)로 옮기고 about을 읽는다. 시계는 앞으로만 간다 — at이 지금보다 이르면 준비 실패로 멈춘다. */
  Result readAboutAt(GoogleDriveSession session, long at, Response reply) {
    if (at < clock.millis())
      throw new IllegalStateException(
          "given 준비 실패: 시계를 " + clock.millis() + "에서 " + at + "로 되돌릴 수 없다(사례 표는 시각 순이어야 한다)");
    clock.advance(Duration.ofMillis(at - clock.millis()));
    dataReply = reply;
    return resultOf(() -> session.json(GoogleService.DRIVE, ABOUT, TIMEOUT));
  }

  /** 다운로드 경로의 원본을 maxBytes 상한으로 받는다. */
  byte[] download(GoogleDriveSession session, long maxBytes) {
    return session.download(DOWNLOADED_FILE, TIMEOUT, maxBytes);
  }

  Result read(GoogleDriveSession session, GoogleService service, String path) {
    return resultOf(() -> session.json(service, path, TIMEOUT));
  }

  /** 실패를 던지는 호출의 예외(실패하지 않으면 given 준비 실패). */
  RuntimeException failureOf(Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      return e;
    }
    throw new IllegalStateException("given 준비 실패: 호출이 실패해야 한다");
  }

  // ---- 관찰 ----

  List<Event> events() {
    return List.copyOf(events);
  }

  /** 가짜 서버가 받은 요청의 경로(토큰 포함). */
  List<String> requestedPaths() {
    return http.received().stream().map(r -> unprefixed(r.path())).toList();
  }

  /** origin 접두를 뗀 경로. */
  private static String unprefixed(String path) {
    for (String prefix : GoogleDriveAssembly.ORIGIN_PREFIXES.values())
      if (path.startsWith(prefix + "/")) return path.substring(prefix.length());
    return path;
  }

  /** 다운로드 요청의 쿼리. */
  Map<String, String> downloadParams() {
    return http.received().stream()
        .filter(r -> r.path().endsWith("/" + DOWNLOADED_FILE))
        .findFirst()
        .orElseThrow()
        .params();
  }

  /**
   * 전제 확인: 첫 호스트가 이 경로 요청을 받았다(그 응답이 각본의 리다이렉트다). 받지 않았다면 리다이렉트가 일어나지 않은 것이라 "다른 호스트로 가지 않았다"는 관찰이
   * 아무것도 증명하지 못한다.
   */
  void requireRedirected(String path) {
    if (!requestedPaths().contains(path))
      throw new IllegalStateException(
          "given 준비 실패: 첫 호스트가 " + path + " 요청에 리다이렉트로 답했다 — " + requestedPaths());
  }

  /** 리다이렉트 Location의 다른 호스트가 받은 요청 수. */
  int otherHostRequests() {
    return other.received().size();
  }

  /** 토큰 끝점이 받은 요청들. */
  List<Recorded> tokenRequests() {
    return http.received().stream().filter(r -> unprefixed(r.path()).equals(TOKEN)).toList();
  }

  private static Result resultOf(Runnable call) {
    try {
      call.run();
      return new Result(true, null);
    } catch (CallFailureException e) {
      return new Result(false, FailureView.of(e.failure()));
    } catch (RuntimeException e) {
      return new Result(false, FailureView.of(GoogleErrors.classify(e)));
    }
  }

  static List<Response> responses(JsonNode replies) {
    List<Response> out = new ArrayList<>();
    replies.forEach(r -> out.add(GoogleDriveFixtures.response(r)));
    return out;
  }

  @Override
  public void close() {
    try {
      http.close();
    } finally {
      other.close();
    }
  }
}
