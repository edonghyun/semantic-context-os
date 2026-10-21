package io.scos.connector.notion.internal;

import io.scos.connector.notion.NotionAssembly;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.connector.CallFailure;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Notion 클라이언트 테스트의 연결: 데이터셋 가짜 Notion과 그것을 부르는 제품 클라이언트. 본문은 "어떤 응답에 무엇을 부르면 무엇이 나오는가"만 쓴다. 닫을 때
 * 데이터셋 밖 요청이 있으면 실패한다.
 */
final class NotionClientScenario implements AutoCloseable {
  static final Duration TIMEOUT = ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT;
  static final String ROOT_PAGE = "/v1/pages/root-wiki";

  /** 단건·목록 조회 하나(TS 표의 operation 이름). */
  enum Operation {
    PAGE("page", "GET", ROOT_PAGE),
    DATABASE("database", "GET", "/v1/databases/db-tasks"),
    DATA_SOURCE("data_source", "GET", "/v1/data_sources/ds-tasks"),
    SEARCH("search", "POST", "/v1/search"),
    BLOCKS("blocks", "GET", "/v1/blocks/root-wiki/children"),
    QUERY("query", "POST", "/v1/data_sources/ds-tasks/query");

    final String wire;
    final String method;
    final String path;

    Operation(String wire, String method, String path) {
      this.wire = wire;
      this.method = method;
      this.path = path;
    }

    Object invoke(NotionApiClient client, String token) {
      return switch (this) {
        case PAGE -> client.retrievePage(token, "root-wiki", TIMEOUT);
        case DATABASE -> client.retrieveDatabase(token, "db-tasks", TIMEOUT);
        case DATA_SOURCE -> client.retrieveDataSource(token, "ds-tasks", TIMEOUT);
        case SEARCH -> client.searchPages(token, Optional.empty(), SearchPhase.ACTIVE, TIMEOUT);
        case BLOCKS -> client.listBlockChildren(token, "root-wiki", Optional.empty(), TIMEOUT);
        case QUERY -> client.queryDataSource(token, "ds-tasks", Optional.empty(), TIMEOUT);
      };
    }

    static Operation of(String wire) {
      for (Operation o : values()) if (o.wire.equals(wire)) return o;
      throw new IllegalArgumentException(wire);
    }
  }

  private final FakeNotionServer notion;
  private final NotionApiClient client;

  NotionClientScenario() {
    try {
      notion = new FakeNotionServer(NotionFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    client = NotionAssembly.client(notion.baseUrl(), TIMEOUT);
  }

  // ---- 준비 ----

  /** 이 조회가 이 상태·본문(·Retry-After·Content-Type)으로 답한다. */
  void answers(Operation operation, int status, String body, Map<String, String> headers) {
    notion.respond(operation.method, operation.path, new Response(status, body, headers));
  }

  /** 이 조회가 200으로 이 본문을 돌려준다. */
  void answersOk(Operation operation, JsonNode body) {
    answers(operation, HttpURLConnection.HTTP_OK, body.toString(), Map.of());
  }

  // ---- 실행 ----

  Object call(Operation operation) {
    return operation.invoke(client, notion.token());
  }

  /** 조회 결과를 TS 표의 outcome 모양으로: 성공이면 {ok, (id | count·nextCursor)}, 실패면 {ok:false, failure}. */
  Outcome outcome(Operation operation) {
    try {
      return Outcome.success(call(operation));
    } catch (NotionApiException e) {
      return Outcome.failed(FailureView.of(NotionErrors.classify(e)));
    }
  }

  /** 닫힌 포트를 부른 실패의 분류(TS fetch failed — HTTP 응답이 없다). */
  static CallFailure transportFailure() {
    NotionApiClient unreachable = NotionAssembly.client(NotionAssembly.closedPort(), TIMEOUT);
    try {
      unreachable.retrievePage("token", "root-wiki", TIMEOUT);
    } catch (RuntimeException e) {
      return NotionErrors.classify(e);
    }
    throw new IllegalStateException("given 준비 실패: 닫힌 포트 요청은 실패해야 한다");
  }

  /** 루트 페이지 조회가 302로 같은 가짜 서버의 다른 경로를 가리키고, 그 경로는 정상 페이지로 답한다(따라가면 성공하는 각본). 그 경로를 돌려준다. */
  String rootPageRedirectsToValidPage() {
    String target = "/v1/pages/redirected";
    notion.respond(
        "GET",
        target,
        new Response(
            200,
            "{\"object\":\"page\",\"id\":\"root-wiki\",\"created_time\":\"t\",\"last_edited_time\":\"t\",\"in_trash\":false}",
            Map.of()));
    notion.respond(
        "GET", ROOT_PAGE, new Response(302, "", Map.of("Location", notion.baseUrl() + target)));
    return target;
  }

  /** API origin을 끝에 /를 붙여 준 클라이언트로 부른다. */
  <T> T readWithTrailingSlash(Function<NotionApiClient, T> call) {
    return call.apply(NotionAssembly.client(notion.baseUrl() + "/", TIMEOUT));
  }

  <T> T read(Function<NotionApiClient, T> call) {
    return call.apply(client);
  }

  String token() {
    return notion.token();
  }

  // ---- 관찰 ----

  List<Recorded> requests() {
    return notion.requests();
  }

  /** 부재(다른 경로로 가지 않음)를 보기 전에 이 경로를 요청했음을 확보한다 — 아니면 given 준비 실패. */
  void requireRequested(String path) {
    if (notion.requests().stream().noneMatch(r -> r.path().equals(path)))
      throw new IllegalStateException("given 준비 실패: " + path + "를 요청해야 한다 — " + requests());
  }

  @Override
  public void close() {
    notion.close();
  }

  /** TS 표의 outcome. 성공 값은 단건이면 id(페이지만 — 나머지는 null), 목록이면 개수와 다음 커서. */
  record Outcome(boolean ok, String id, Integer count, String nextCursor, FailureView failure) {
    static Outcome success(Object value) {
      if (value instanceof NotionApiClient.ListPage page)
        return new Outcome(true, null, page.results().size(), page.nextCursor().orElse(null), null);
      return new Outcome(true, null, null, null, null);
    }

    static Outcome failed(FailureView failure) {
      return new Outcome(false, null, null, null, failure);
    }

    /** id는 비교하지 않는다(단건 성공의 id는 요청한 id다 — 별도 테스트). */
    static Outcome expected(JsonNode o) {
      if (!o.get("ok").asBoolean()) return failed(FailureView.expected(o.get("failure")));
      return o.has("count")
          ? new Outcome(
              true,
              null,
              o.get("count").asInt(),
              o.get("nextCursor").isNull() ? null : o.get("nextCursor").asString(),
              null)
          : new Outcome(true, null, null, null, null);
    }
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

  /** 표의 응답 헤더(Content-Type, 있으면 Retry-After). */
  static Map<String, String> headersOf(JsonNode row) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", row.get("contentType").asString());
    if (!row.get("retryAfter").isNull())
      headers.put("Retry-After", row.get("retryAfter").asString());
    return headers;
  }
}
