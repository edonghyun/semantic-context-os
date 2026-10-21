package io.scos.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 공급자 가짜 HTTP의 공용 기반(TESTING §4·§3.6): 등록된 "메서드 경로"만 응답하고, 잘못된 요청은 인증 검사·장애 각본보다 먼저 미등록으로 기록한다.
 * 커넥터별 가짜(`FakeSlackServer` 등)는 이 위에 라우트 응답과 고장 몸체만 얹는다.
 */
@DisplayName("가짜 HTTP 기반은 등록된 요청에만 답하고 잘못된 요청을 숨기지 않는다(TESTING §4.2)")
class FakeHttpServerTest {
  static final String ITEMS = "/api/items";
  static final String SLOW = "/api/slow";
  static final String TOKEN = "Bearer t";

  /** 요청 하나의 hang 방지 상한 — 응답이 오지 않는 구현은 이 시간 뒤 실패한다. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

  static final String TOKEN_ENDPOINT = "/oauth/token";

  /** 같은 경로로 동시에 보낼 요청 수 — 게이트에는 그중 하나만 걸려야 한다. */
  static final int SIMULTANEOUS = 16;

  static final FakeHttpServer.Response UNAUTHORIZED = FakeHttpServer.status(401, "unauthorized");
  static final FakeHttpServer.Response UNAVAILABLE = FakeHttpServer.status(503, "busy");

  final FakeHttpServer server = newServer();
  final CountDownLatch slowGate = new CountDownLatch(1);
  CompletableFuture<HttpResponse<String>> held;

  static FakeHttpServer newServer() {
    try {
      return new FakeHttpServer();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 이 클래스는 가짜 자체를 시험하므로(미등록·실패를 일부러 만든다) 검사 없이 닫는다. */
  @AfterEach
  void stop() {
    openGate();
    server.abandon();
  }

  void givenItemsRoute() {
    server.on("GET", ITEMS, r -> FakeHttpServer.ok("items"));
  }

  void givenTokenRequired() {
    server.requireHeader("Authorization", TOKEN, UNAUTHORIZED);
  }

  HttpRequest request(String method, String path, String authorization) {
    return HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
        .method(method, HttpRequest.BodyPublishers.noBody())
        .header("Authorization", authorization)
        .timeout(WAIT_LIMIT)
        .build();
  }

  HttpResponse<String> send(String method, String path, String authorization) throws Exception {
    return HttpClient.newHttpClient()
        .send(request(method, path, authorization), HttpResponse.BodyHandlers.ofString());
  }

  void openGate() {
    slowGate.countDown();
  }

  String heldResponseBody() throws Exception {
    return held.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS).body();
  }

  /** SLOW 경로를 등록하고 게이트를 건다. 게이트는 열지 않는다. */
  void givenGatedSlowRoute() {
    server.on("GET", SLOW, r -> FakeHttpServer.ok("slow"));
    server.gateOn(SLOW, slowGate, path -> {});
  }

  /** 게이트 경로로 n개를 동시에 보낸다. */
  List<CompletableFuture<HttpResponse<String>>> sendSimultaneously(int n) {
    HttpClient client = HttpClient.newHttpClient();
    return IntStream.range(0, n)
        .mapToObj(
            i ->
                client.sendAsync(request("GET", SLOW, TOKEN), HttpResponse.BodyHandlers.ofString()))
        .toList();
  }

  /** 응답한 요청이 expected개가 될 때까지(상한은 hang 방지) 기다린 뒤, 그때 응답한 수. */
  static long awaitAnswered(List<CompletableFuture<HttpResponse<String>>> sent, int expected) {
    Eventually.await(
        expected + "개 응답",
        () -> sent.stream().filter(CompletableFuture::isDone).count() >= expected,
        WAIT_LIMIT);
    return sent.stream().filter(CompletableFuture::isDone).count();
  }

  /** 게이트에 걸리는 경로로 요청을 보내고, 서버가 그 요청을 받을 때까지 기다린다. 응답은 게이트가 열린 뒤 {@link #held}로 온다. */
  void givenHeldRequest() throws Exception {
    CompletableFuture<String> arrived = new CompletableFuture<>();
    server.on("GET", SLOW, r -> FakeHttpServer.ok("slow"));
    server.gateOn(SLOW, slowGate, arrived::complete);
    held =
        HttpClient.newHttpClient()
            .sendAsync(request("GET", SLOW, TOKEN), HttpResponse.BodyHandlers.ofString());
    arrived.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Nested
  @DisplayName("등록되지 않은 경로로 보내면")
  class UnknownPath {
    @BeforeEach
    void givenOtherRouteOnly() {
      givenItemsRoute();
    }

    @Test
    @DisplayName("404로 답한다")
    void answersNotFound() throws Exception {
      // when
      HttpResponse<String> response = send("GET", "/api/other", TOKEN);

      // then
      assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("assertNoUnmatched에서 메서드와 경로를 밝혀 실패한다")
    void failsOnAssertNoUnmatched() throws Exception {
      // when
      send("GET", "/api/other", TOKEN);

      // then
      assertThatThrownBy(server::assertNoUnmatched).hasMessageContaining("GET /api/other");
    }
  }

  @Nested
  @DisplayName("등록된 경로에 다른 메서드로 보내면")
  class WrongMethod {
    @BeforeEach
    void givenRoute() {
      givenItemsRoute();
    }

    @Test
    @DisplayName("405로 답한다")
    void answersMethodNotAllowed() throws Exception {
      // when
      HttpResponse<String> response = send("POST", ITEMS, TOKEN);

      // then
      assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("장애 각본이 걸린 경로여도 미등록으로 기록된다")
    void recordedEvenWhenFaulted() throws Exception {
      // given
      server.failNext(ITEMS, 1, UNAVAILABLE);

      // when
      send("POST", ITEMS, TOKEN);

      // then
      assertThatThrownBy(server::assertNoUnmatched).hasMessageContaining("POST " + ITEMS);
    }

    @Test
    @DisplayName("인증 헤더가 틀려도 미등록으로 기록된다")
    void recordedEvenWithWrongCredential() throws Exception {
      // given
      givenTokenRequired();

      // when
      send("POST", ITEMS, "Bearer wrong");

      // then
      assertThatThrownBy(server::assertNoUnmatched).hasMessageContaining("POST " + ITEMS);
    }

    @Test
    @DisplayName("장애 각본의 횟수를 쓰지 않는다")
    void doesNotSpendFaultBudget() throws Exception {
      // given
      server.failNext(ITEMS, 1, UNAVAILABLE);
      send("POST", ITEMS, TOKEN);

      // when
      HttpResponse<String> response = send("GET", ITEMS, TOKEN);

      // then
      assertThat(response.statusCode()).isEqualTo(503);
    }
  }

  @Nested
  @DisplayName("토큰을 요구하면")
  class TokenRequired {
    @BeforeEach
    void givenRouteWithToken() {
      givenItemsRoute();
      givenTokenRequired();
    }

    @Test
    @DisplayName("요구한 헤더가 다르면 거절 응답을 준다")
    void rejectsWrongRequiredHeader() throws Exception {
      // when
      HttpResponse<String> response = send("GET", ITEMS, "Bearer wrong");

      // then
      assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("대조군: 요구한 헤더가 맞으면 등록된 응답을 준다")
    void answersWithMatchingRequiredHeader() throws Exception {
      // when
      HttpResponse<String> response = send("GET", ITEMS, TOKEN);

      // then
      assertThat(response.body()).isEqualTo("items");
    }

    @Test
    @DisplayName("검사에서 뺀 라우트(토큰 끝점)는 기본 검사를 받지 않는다")
    void exemptRouteSkipsDefaultCheck() throws Exception {
      // given
      server.on("POST", TOKEN_ENDPOINT, r -> FakeHttpServer.ok("{}"));
      server.withoutHeaderCheck("POST", TOKEN_ENDPOINT);

      // when
      int status = send("POST", TOKEN_ENDPOINT, "").statusCode();

      // then
      assertThat(status).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("한 라우트에만 헤더 검사를 걸면")
  class RouteHeaders {
    @BeforeEach
    void givenItemsRouteChecked() {
      givenItemsRoute();
      server.requireHeader("GET", ITEMS, "Authorization", TOKEN, UNAUTHORIZED);
    }

    @Test
    @DisplayName("다른 라우트에는 적용되지 않는다")
    void appliesOnlyToItsRoute() throws Exception {
      // given
      server.on("GET", SLOW, r -> FakeHttpServer.ok("slow"));

      // when
      int other = send("GET", SLOW, "Bearer wrong").statusCode();

      // then
      assertThat(other).isEqualTo(200);
    }

    @Test
    @DisplayName("그 라우트에는 검사를 건다")
    void checksItsRoute() throws Exception {
      // when
      int status = send("GET", ITEMS, "Bearer wrong").statusCode();

      // then
      assertThat(status).isEqualTo(401);
    }
  }

  @Nested
  @DisplayName("등록된 경로로 보내면")
  class RegisteredRoute {
    @BeforeEach
    void givenRoute() {
      givenItemsRoute();
    }

    @Test
    @DisplayName("장애 각본은 정한 횟수만 응답하고 그 뒤는 등록된 응답이다")
    void faultsOnlyForBudget() throws Exception {
      // given
      server.failNext(ITEMS, 2, UNAVAILABLE);

      // when
      int first = send("GET", ITEMS, TOKEN).statusCode();
      int second = send("GET", ITEMS, TOKEN).statusCode();
      int third = send("GET", ITEMS, TOKEN).statusCode();

      // then
      assertThat(new int[] {first, second, third}).containsExactly(503, 503, 200);
    }

    @Test
    @DisplayName("받은 요청의 헤더를 순서대로 기록한다")
    void recordsHeadersInOrder() throws Exception {
      // when
      send("GET", ITEMS, "Bearer a");
      send("GET", ITEMS, "Bearer b");

      // then
      assertThat(server.received())
          .extracting(r -> r.header("authorization"))
          .containsExactly("Bearer a", "Bearer b");
    }

    @Test
    @DisplayName("쿼리 파라미터를 디코드해 돌려준다")
    void decodesQueryParameters() throws Exception {
      // when
      send("GET", ITEMS + "?cursor=page%3A2&oldest=1.5", TOKEN);

      // then
      assertThat(server.received().getFirst().params())
          .containsEntry("cursor", "page:2")
          .containsEntry("oldest", "1.5");
    }
  }

  @Test
  @DisplayName("inOrder 응답은 차례로 쓰고 다 쓰면 마지막을 반복한다")
  void inOrderRepeatsLast() throws Exception {
    // given
    server.on("GET", ITEMS, FakeHttpServer.inOrder(UNAVAILABLE, FakeHttpServer.ok("first-ok")));

    // when
    int first = send("GET", ITEMS, TOKEN).statusCode();
    int second = send("GET", ITEMS, TOKEN).statusCode();
    int third = send("GET", ITEMS, TOKEN).statusCode();

    // then
    assertThat(new int[] {first, second, third}).containsExactly(503, 200, 200);
  }

  @Nested
  @DisplayName("게이트에 걸린 요청이 있으면")
  class WhileGateHeld {
    @BeforeEach
    void givenHeld() throws Exception {
      givenItemsRoute();
      givenHeldRequest();
    }

    @Test
    @DisplayName("다른 요청은 그대로 응답한다")
    void otherRequestsStillAnswer() throws Exception {
      // when
      HttpResponse<String> response = send("GET", ITEMS, TOKEN);

      // then
      assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("걸린 요청은 게이트가 열린 뒤 응답한다")
    void heldRequestAnswersAfterOpen() throws Exception {
      // given
      if (held.isDone()) throw new IllegalStateException("given 준비 실패: 열리기 전에 응답이 왔다");

      // when
      openGate();

      // then
      assertThat(heldResponseBody()).isEqualTo("slow");
    }
  }

  @Nested
  @DisplayName("경로에 게이트를 걸면")
  class Gated {
    @BeforeEach
    void givenGate() {
      givenGatedSlowRoute();
    }

    /**
     * 한계: 게이트를 원자적으로 걷지 않는 변형(읽은 뒤 따로 비우기)은 이 테스트로 잡히지 않는다 — 두 요청이 읽기와 비우기 사이에 함께 들어와야 드러나는데 그 창이
     * 좁다(손 변형 5회 모두 녹색). 결정적으로 만들려면 읽기와 비우기 사이에 시험용 고리가 필요해 두지 않았다. 이 테스트는 "붙잡힌 요청 외에는 모두 답한다"를
     * 고정한다.
     */
    @Test
    @DisplayName("같은 경로 동시 요청 중 게이트에는 하나만 걸린다")
    void gateHoldsExactlyOneOfSimultaneousRequests() {
      // when
      List<CompletableFuture<HttpResponse<String>>> sent = sendSimultaneously(SIMULTANEOUS);
      long answered = awaitAnswered(sent, SIMULTANEOUS - 1);

      // then
      assertThat(answered).isEqualTo(SIMULTANEOUS - 1);
    }

    @Test
    @DisplayName("게이트를 걷으면 이후 요청은 붙잡지 않는다")
    void clearedGateHoldsNothing() throws Exception {
      // when
      server.clearGate();
      int status = send("GET", SLOW, TOKEN).statusCode();

      // then
      assertThat(status).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("동시 수를 재는 중에")
  class InFlightHold {
    static final int TOGETHER = 3;

    /** 모이지 않을 때 기다리는 상한 — 하나씩 오는 사례는 요청마다 이만큼 기다린 뒤 답을 받는다. */
    static final Duration SHORT_LIMIT = Duration.ofMillis(200);

    /** n개를 동시에 보내고 모두 답을 받는다(상한은 hang 방지). */
    List<HttpResponse<String>> sendTogether(int n) throws Exception {
      HttpClient client = HttpClient.newHttpClient();
      List<CompletableFuture<HttpResponse<String>>> sent =
          IntStream.range(0, n)
              .mapToObj(
                  i ->
                      client.sendAsync(
                          request("GET", ITEMS, TOKEN), HttpResponse.BodyHandlers.ofString()))
              .toList();
      List<HttpResponse<String>> answered = new ArrayList<>();
      for (var f : sent) answered.add(f.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS));
      return answered;
    }

    @Test
    @DisplayName("n개가 함께 들어오면 모두 답하고 maxInFlight는 n이다")
    void countsRequestsHeldTogether() throws Exception {
      // given
      givenItemsRoute();
      server.holdUntilInFlight(ITEMS, TOGETHER, WAIT_LIMIT);

      // when
      List<HttpResponse<String>> answered = sendTogether(TOGETHER);

      // then
      assertThat(answered).extracting(HttpResponse::statusCode).containsOnly(200);
      assertThat(server.maxInFlight()).isEqualTo(3);
    }

    @Test
    @DisplayName("하나씩 오면 상한 뒤 답하고 maxInFlight는 1이다")
    void countsOneWhenSequential() throws Exception {
      // given
      givenItemsRoute();
      server.holdUntilInFlight(ITEMS, TOGETHER, SHORT_LIMIT);

      // when
      int first = send("GET", ITEMS, TOKEN).statusCode();
      int second = send("GET", ITEMS, TOKEN).statusCode();

      // then
      assertThat(new int[] {first, second}).containsExactly(200, 200);
      assertThat(server.maxInFlight()).isEqualTo(1);
    }

    @Test
    @DisplayName("접두가 다른 요청은 세지 않는다")
    void ignoresOtherPrefix() throws Exception {
      // given
      server.on("GET", SLOW, r -> FakeHttpServer.ok("slow"));
      server.holdUntilInFlight(ITEMS, TOGETHER, WAIT_LIMIT);

      // when
      int status = send("GET", SLOW, TOKEN).statusCode();

      // then
      if (status != 200)
        throw new IllegalStateException("given 준비 실패: 다른 접두 요청도 답을 받아야 한다 — " + status);
      assertThat(server.maxInFlight()).isZero();
    }
  }

  @Nested
  @DisplayName("닫을 때")
  class Close {
    @Test
    @DisplayName("미등록 요청이 있었으면 실패한다")
    void failsOnUnmatched() throws Exception {
      // given
      givenItemsRoute();
      send("GET", "/api/other", TOKEN);

      // when·then
      assertThatThrownBy(server::close).hasMessageContaining("GET /api/other");
    }

    @Test
    @DisplayName("라우트 처리기가 던졌으면 500으로 답한 뒤 실패한다")
    void failsOnHandlerException() throws Exception {
      // given
      server.on(
          "GET",
          ITEMS,
          r -> {
            throw new IllegalStateException("handler broke");
          });
      int status = send("GET", ITEMS, TOKEN).statusCode();
      if (status != 500)
        throw new IllegalStateException("given 준비 실패: 처리기 예외는 500으로 답해야 한다 — " + status);

      // when·then
      assertThatThrownBy(server::close).hasMessageContaining("handler broke");
    }
  }
}
