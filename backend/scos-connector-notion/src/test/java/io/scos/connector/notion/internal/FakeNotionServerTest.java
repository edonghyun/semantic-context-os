package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 가짜 Notion 자체(TESTING §3.6 2층, §4.2 C7): 데이터셋 밖 요청과 버전 헤더가 다른 요청이 조용히 지나가지 않고, 각본 어휘(차례 응답·갈래
 * phase·crashAt·holdAt·fault·respond·요청 상한)가 말한 요청에만 걸린다. 가짜가 틀리면 Notion fixture·계약·kill 테스트가 함께
 * 거짓말한다.
 */
@DisplayName("가짜 Notion은 데이터셋·각본대로 답하고 밖 요청을 드러낸다(C7)")
class FakeNotionServerTest {
  static final String SEARCH = "/v1/search";
  static final String JSON_TYPE = "application/json";

  /** 답을 기다리는 상한 — 가짜가 잘못 붙잡은 요청이 테스트를 멈추지 않고 실패로 끝나게. */
  static final Duration REPLY_LIMIT = Duration.ofSeconds(10);

  static final String WIKI_CHILDREN = "/v1/blocks/root-wiki/children";
  static final String WIKI_FIRST_PAGE = WIKI_CHILDREN + "?page_size=100";
  static final String WIKI_SECOND_PAGE = WIKI_FIRST_PAGE + "&start_cursor=cur-wiki-2";
  static final String TASKS_QUERY = "/v1/data_sources/ds-tasks/query";
  static final String TASKS_FIRST_PAGE = "{\"page_size\":100}";
  static final String TASKS_SECOND_PAGE = "{\"page_size\":100,\"start_cursor\":\"cur-rows-2\"}";

  /** fixture 기록 모양의 요청(crashAt·holdAt 인자): ds-tasks 행 둘째 페이지 — TS 생성기의 중단 지점과 같다. */
  static final JsonNode TASKS_SECOND_PAGE_REQUEST =
      NotionFixtures.JSON.readTree(
          """
          {"method": "POST", "path": "/v1/data_sources/ds-tasks/query", "params": {},
           "body": "{\\"page_size\\":100,\\"start_cursor\\":\\"cur-rows-2\\"}"}
          """);

  /** fixture 기록 모양의 GET 요청: 루트 위키 자식 첫 페이지. */
  static final JsonNode WIKI_FIRST_PAGE_REQUEST =
      NotionFixtures.JSON.readTree(
          """
          {"method": "GET", "path": "/v1/blocks/root-wiki/children",
           "params": {"page_size": "100"}, "body": ""}
          """);

  final FakeNotionServer notion = newServer();

  static FakeNotionServer newServer() {
    try {
      return new FakeNotionServer(NotionFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterEach
  void stop() {
    notion.abandon();
  }

  record Reply(int status, String body) {}

  HttpRequest.Builder request(String pathAndQuery) {
    return HttpRequest.newBuilder(URI.create(notion.baseUrl() + pathAndQuery))
        .timeout(REPLY_LIMIT)
        .header("Authorization", "Bearer " + notion.token())
        .header("Notion-Version", notion.apiVersion());
  }

  Reply get(String pathAndQuery) throws Exception {
    return send(request(pathAndQuery).GET().build());
  }

  Reply post(String path, String body) throws Exception {
    return send(
        request(path)
            .header("Content-Type", JSON_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  static Reply send(HttpRequest request) throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(), response.body());
  }

  List<Integer> statusesOf(int times, String pathAndQuery) throws Exception {
    List<Integer> statuses = new ArrayList<>();
    for (int i = 0; i < times; i++) statuses.add(get(pathAndQuery).status());
    return statuses;
  }

  int search(String body, String version) throws Exception {
    return search(body, version, JSON_TYPE);
  }

  int search(String body, String version, String contentType) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(notion.baseUrl() + SEARCH))
            .header("Authorization", "Bearer " + notion.token())
            .header("Notion-Version", version)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  @DisplayName("데이터셋에 없는 POST 본문은 미등록으로 남아 검사에서 실패한다")
  void failsOnUnknownBody() throws Exception {
    // when
    search("{\"page_size\":7}", notion.apiVersion());

    // then
    assertThatThrownBy(notion::assertNoUnmatched).hasMessageContaining("page_size\":7");
  }

  @Test
  @DisplayName("Notion-Version이 데이터셋과 다르면 401로 답한다")
  void rejectsOtherVersion() throws Exception {
    // when
    int status = search("{}", "2022-06-28");

    // then
    assertThat(status).isEqualTo(401);
  }

  @Test
  @DisplayName("JSON이 아닌 Content-Type의 POST는 실패로 남는다")
  void failsOnNonJsonPost() throws Exception {
    // when
    search("{}", notion.apiVersion(), "text/plain");

    // then
    assertThatThrownBy(notion::assertNoUnmatched).hasMessageContaining("Content-Type");
  }

  @Test
  @DisplayName("차례 응답을 다 쓰면 마지막을 반복한다(root-retro: 429 뒤 200)")
  void repeatsLastResponse() throws Exception {
    // when
    List<Integer> statuses = statusesOf(3, "/v1/pages/root-retro");

    // then
    assertThat(statuses).containsExactly(429, 200, 200);
  }

  @Nested
  @DisplayName("데이터셋 갈래를 바꾸면")
  class Phases {
    @Test
    @DisplayName("대조군: 처음 갈래에서 동기화 블록 자식은 404다")
    void initialServesBase() throws Exception {
      // when
      Reply reply = get("/v1/blocks/blk-wiki-synced/children?page_size=100");

      // then
      assertThat(reply.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("EDITED면 afterEdit에 있는 라우트는 바뀐 응답이다")
    void editedServesAfterEdit() throws Exception {
      // given
      notion.phase(FakeNotionServer.Phase.EDITED);

      // when
      Reply reply = get(WIKI_FIRST_PAGE);

      // then
      assertThat(reply.body()).isEqualTo(datasetText("afterEdit", WIKI_CHILDREN));
    }

    @Test
    @DisplayName("EDITED여도 afterEdit에 없는 라우트는 처음 데이터셋 응답이다")
    void editedFallsBackToBase() throws Exception {
      // given
      notion.phase(FakeNotionServer.Phase.EDITED);

      // when
      Reply reply = get(WIKI_SECOND_PAGE);

      // then
      assertThat(reply.body()).isEqualTo(datasetText("routes", WIKI_CHILDREN, "cur-wiki-2"));
    }

    @Test
    @DisplayName("SHARED면 afterShare 라우트로 답한다(동기화 블록 자식 200)")
    void sharedServesAfterShare() throws Exception {
      // given
      notion.phase(FakeNotionServer.Phase.SHARED);

      // when
      Reply reply = get("/v1/blocks/blk-wiki-synced/children?page_size=100");

      // then
      assertThat(reply.status()).isEqualTo(200);
    }
  }

  /** 데이터셋 갈래(routes·afterEdit)에서 이 경로(커서가 있으면 그 커서)의 첫 응답 원문. */
  static String datasetText(String branch, String path, String... cursor) {
    for (JsonNode r : NotionFixtures.dataset().get(branch)) {
      JsonNode c = r.get("params").get("start_cursor");
      boolean cursorMatches =
          cursor.length == 0 ? c == null : c != null && c.asString().equals(cursor[0]);
      if (r.get("path").asString().equals(path) && cursorMatches)
        return r.get("responses").get(0).get("text").asString();
    }
    throw new IllegalStateException("given 준비 실패: 데이터셋 " + branch + "에 " + path + "가 없다");
  }

  @Nested
  @DisplayName("POST 요청에 crashAt을 걸면")
  class CrashAtPost {
    @BeforeEach
    void givenCrashAtSecondPage() {
      notion.crashAt(TASKS_SECOND_PAGE_REQUEST);
    }

    @Test
    @DisplayName("메서드·경로·본문이 같은 요청은 401이다")
    void sameRequestIsUnauthorized() throws Exception {
      // when
      Reply reply = post(TASKS_QUERY, TASKS_SECOND_PAGE);

      // then
      assertThat(reply.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("같은 경로라도 본문이 다르면 데이터셋으로 답한다")
    void otherBodyServesDataset() throws Exception {
      // when
      Reply reply = post(TASKS_QUERY, TASKS_FIRST_PAGE);

      // then
      assertThat(reply.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("clearCrash 뒤에는 그 요청도 데이터셋으로 답한다")
    void clearedServesDataset() throws Exception {
      // given
      notion.clearCrash();

      // when
      Reply reply = post(TASKS_QUERY, TASKS_SECOND_PAGE);

      // then
      assertThat(reply.status()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("GET 요청에 crashAt을 걸면")
  class CrashAtGet {
    @BeforeEach
    void givenCrashAtFirstPage() {
      notion.crashAt(WIKI_FIRST_PAGE_REQUEST);
    }

    @Test
    @DisplayName("경로와 쿼리가 같은 요청은 401이다")
    void sameRequestIsUnauthorized() throws Exception {
      // when
      Reply reply = get(WIKI_FIRST_PAGE);

      // then
      assertThat(reply.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("같은 경로라도 쿼리가 다르면 데이터셋으로 답한다")
    void otherQueryServesDataset() throws Exception {
      // when
      Reply reply = get(WIKI_SECOND_PAGE);

      // then
      assertThat(reply.status()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("holdAt을 건 요청이 오면")
  class HoldAt {
    final CountDownLatch arrived = new CountDownLatch(1);
    CompletableFuture<HttpResponse<String>> held;

    @BeforeEach
    void givenHeldRequestArrived() throws Exception {
      notion.holdAt(TASKS_SECOND_PAGE_REQUEST, arrived::countDown);
      held =
          HttpClient.newHttpClient()
              .sendAsync(
                  request(TASKS_QUERY)
                      .header("Content-Type", JSON_TYPE)
                      .POST(HttpRequest.BodyPublishers.ofString(TASKS_SECOND_PAGE))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      if (!arrived.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: 붙잡을 요청이 도착하지 않았다");
    }

    @Test
    @DisplayName("뒤에 온 요청이 답을 받은 뒤에도 붙잡은 요청에는 답하지 않는다")
    void doesNotAnswerUntilReleased() throws Exception {
      // when
      post(TASKS_QUERY, TASKS_FIRST_PAGE);

      // then
      assertThat(held).isNotDone();
    }

    @Test
    @DisplayName("같은 경로의 다른 본문 요청은 그대로 답한다")
    void otherBodyPassesThrough() throws Exception {
      // when
      Reply reply = post(TASKS_QUERY, TASKS_FIRST_PAGE);

      // then
      assertThat(reply.status()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("응답이 있는 장애 각본을 쿼리로 좁혀 걸면")
  class FaultWithResponses {
    @BeforeEach
    void givenFaultOnSecondPage() {
      notion.fault(
          NotionFixtures.JSON.readTree(
              """
              {"method": "GET", "path": "/v1/blocks/root-wiki/children",
               "params": {"page_size": "100", "start_cursor": "cur-wiki-2"},
               "responses": [{"status": 503, "text": "{}", "headers": {}},
                             {"status": 500, "text": "{}", "headers": {}}]}
              """));
    }

    @Test
    @DisplayName("맞는 요청에는 각본 응답을 차례로 쓰고 다 쓰면 마지막을 반복한다")
    void servesFaultResponsesInOrder() throws Exception {
      // when
      List<Integer> statuses = statusesOf(3, WIKI_SECOND_PAGE);

      // then
      assertThat(statuses).containsExactly(503, 500, 500);
    }

    @Test
    @DisplayName("쿼리가 다른 요청은 데이터셋으로 답한다")
    void otherQueryServesDataset() throws Exception {
      // when
      Reply reply = get(WIKI_FIRST_PAGE);

      // then
      assertThat(reply.status()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("응답 없는(hang) 장애 각본에 요청이 오면")
  class HangFault {
    final CountDownLatch hung = new CountDownLatch(1);
    CompletableFuture<HttpResponse<String>> pending;

    @BeforeEach
    void givenHangingRequest() throws Exception {
      notion.onHang(hung::countDown);
      notion.fault(
          NotionFixtures.JSON.readTree(
              """
              {"method": "GET", "path": "/v1/blocks/root-wiki/children",
               "params": {"page_size": "100"}, "hang": true}
              """));
      pending =
          HttpClient.newHttpClient()
              .sendAsync(
                  request(WIKI_FIRST_PAGE).GET().build(), HttpResponse.BodyHandlers.ofString());
      if (!hung.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: hang 각본에 요청이 닿지 않았다");
    }

    @Test
    @DisplayName("뒤에 온 요청이 답을 받은 뒤에도 걸린 요청에는 답하지 않는다")
    void doesNotAnswerUntilReleased() throws Exception {
      // when
      get(WIKI_SECOND_PAGE);

      // then
      assertThat(pending).isNotDone();
    }
  }

  @Test
  @DisplayName("respond로 덮은 경로는 쿼리와 무관하게 그 응답이다")
  void respondOverridesAnyQuery() throws Exception {
    // given
    notion.respond("GET", WIKI_CHILDREN, new Response(418, "{\"over\":1}", Map.of()));

    // when
    List<Integer> statuses = List.of(get(WIKI_FIRST_PAGE).status(), get(WIKI_SECOND_PAGE).status());

    // then
    assertThat(statuses).containsExactly(418, 418);
  }

  @Nested
  @DisplayName("요청 상한을 넘으면")
  class RequestBudget {
    final FakeNotionServer small = newServer(2);
    Reply lastWithinBudget;
    Reply overBudget;

    static FakeNotionServer newServer(int budget) {
      try {
        return new FakeNotionServer(NotionFixtures.dataset(), budget);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @BeforeEach
    void givenBudgetSpent() throws Exception {
      sendTo(small, "/v1/pages/root-wiki");
      lastWithinBudget = sendTo(small, "/v1/pages/root-wiki");
      overBudget = sendTo(small, "/v1/pages/root-wiki");
    }

    @AfterEach
    void stopSmall() {
      small.abandon();
    }

    Reply sendTo(FakeNotionServer server, String path) throws Exception {
      return send(
          HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
              .header("Authorization", "Bearer " + server.token())
              .header("Notion-Version", server.apiVersion())
              .build());
    }

    @Test
    @DisplayName("대조군: 상한째 요청까지는 데이터셋으로 답한다")
    void answersWithinBudget() {
      // then
      assertThat(lastWithinBudget.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("상한 다음 요청은 비재시도 400(validation_error)으로 답한다")
    void answersValidationError() {
      // then
      assertThat(overBudget)
          .extracting(Reply::status, r -> r.body().contains("validation_error"))
          .containsExactly(400, true);
    }

    @Test
    @DisplayName("상한 초과를 검사 실패로 남긴다")
    void recordsOverBudget() {
      // then
      assertThatThrownBy(small::assertNoUnmatched).hasMessageContaining("요청 상한 초과");
    }
  }
}
