package io.scos.connector.slack.internal;

import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 가짜 Slack Web API(합성 데이터). harness/fixtures/p4/slack-dataset.json을 그대로 서빙한다 — TS
 * make-p4-fixture.ts의 fakeClient와 같은 커서 규약("page:<다음 인덱스>", 채널 목록은 시작 인덱스). 공용 {@link
 * FakeHttpServer} 위의 얇은 층이다: 이 클래스는 GET 라우트 셋의 응답, 토큰이 다를 때의 invalid_auth(200 + ok:false), Slack
 * 형식의 고장 몸체만 정한다. 미등록 요청 기록·장애 각본 횟수·게이트·요청 기록은 공용 기반의 것이다. 실제 Slack으로는 절대 나가지 않는다.
 */
public final class FakeSlackServer implements AutoCloseable {
  /** 장애 응답: Slack API 오류 코드(JSON) 또는 원문 본문(비-JSON, LB 오류 페이지). */
  public record Fault(String error, int status, String retryAfterSeconds, String rawBody) {
    public static Fault apiError(String code) {
      return new Fault(code, 200, null, null);
    }

    public static Fault rateLimited(int retryAfterSeconds) {
      return new Fault("ratelimited", 429, String.valueOf(retryAfterSeconds), null);
    }

    public static Fault serverError(int status) {
      return new Fault("internal_error", status, null, null);
    }

    public static Fault http(int status, String rawBody) {
      return new Fault(null, status, null, rawBody);
    }
  }

  private static final String JSON_CONTENT_TYPE = "Content-Type";
  private static final String JSON_MEDIA_TYPE = "application/json";
  private static final String CHANNELS = "/api/users.conversations";
  private static final String HISTORY = "/api/conversations.history";
  private static final String REPLIES = "/api/conversations.replies";

  /** 커서 반복 각본이 첫 페이지로 되돌리는 응답 수: 첫 응답이 첫 페이지 커서를 주고, 그 커서로 온 요청에 같은 커서를 한 번 더 준다. */
  private static final int CURSOR_REPEATS = 2;

  /**
   * 요청 상한 — 같은 history 페이지를 끝없이 다시 묻는 결함(변이)을 비재시도 실패(invalid_provider_response)와 검사 실패로 끝낸다. 정상
   * fixture는 run당 7요청이다. 서버 생애의 누적 상한으로, 두 번 수집과 재시도 여유를 둔다.
   */
  static final int MAX_REQUESTS = 128;

  private final ObjectMapper json = new ObjectMapper();
  private final ObjectNode dataset;
  private final FakeHttpServer http;
  private final Map<String, Integer> cursorRepeats = new HashMap<>();
  private final Map<String, Deque<String>> cursorCycles = new HashMap<>();

  public FakeSlackServer(Path datasetFile) throws IOException {
    this(new ObjectMapper().readTree(Files.readString(datasetFile)));
  }

  public FakeSlackServer(JsonNode dataset) throws IOException {
    this.dataset = (ObjectNode) dataset.deepCopy();
    this.http = new FakeHttpServer();
    http.limitRequests(MAX_REQUESTS, apiFailure(200, "invalid_provider_response"));
    http.requireHeader("Authorization", "Bearer " + token(), apiFailure(200, "invalid_auth"));
    http.on("GET", CHANNELS, r -> answer(CHANNELS, this::channels, r));
    http.on("GET", HISTORY, r -> answer(HISTORY, this::history, r));
    http.on("GET", REPLIES, r -> answer(REPLIES, this::replies, r));
    // 옛 본문 fixture에는 디렉터리 데이터가 없다. 인식한 접근 API는 권한을 추정하지 않고 scope 거부로 답한다.
    if (dataset.has("access")) {
      http.on("GET", "/api/auth.test", r -> answer("/api/auth.test", this::auth, r));
      http.on(
          "GET", "/api/conversations.info", r -> answer("/api/conversations.info", this::info, r));
      http.on(
          "GET",
          "/api/conversations.members",
          r -> answer("/api/conversations.members", this::members, r));
      http.on("GET", "/api/users.list", r -> answer("/api/users.list", this::users, r));
    } else {
      http.on("GET", "/api/auth.test", r -> apiFailure(200, "missing_scope"));
    }
  }

  public String baseUrl() {
    return http.baseUrl();
  }

  public synchronized void changeAccess(Consumer<ObjectNode> change) {
    change.accept((ObjectNode) dataset.get("access"));
  }

  public synchronized void clearChannels() {
    ((ArrayNode) dataset.get("channels")).removeAll();
  }

  public synchronized void addAccessChannel(String id, Set<String> members) {
    ((ArrayNode) dataset.path("channels")).add(id);
    ObjectNode access = (ObjectNode) dataset.path("access");
    ((ObjectNode) access.path("conversations"))
        .putObject(id)
        .put("id", id)
        .put("is_private", true)
        .put("is_im", false)
        .put("is_mpim", false)
        .put("is_archived", false);
    ArrayNode page = ((ObjectNode) access.path("memberPages")).putArray(id).addArray();
    members.stream().sorted().forEach(page::add);
  }

  private ObjectNode auth(Map<String, String> query) {
    return json.createObjectNode()
        .put("ok", true)
        .set("team_id", dataset.path("access").path("teamId"));
  }

  private ObjectNode info(Map<String, String> query) {
    JsonNode channel = dataset.path("access").path("conversations").path(query.get("channel"));
    if (channel.isMissingNode())
      return json.createObjectNode().put("ok", false).put("error", "channel_not_found");
    return json.createObjectNode().put("ok", true).set("channel", channel);
  }

  private ObjectNode members(Map<String, String> query) {
    return accessPage(
        dataset.path("access").path("memberPages").path(query.get("channel")), query, "members");
  }

  private ObjectNode users(Map<String, String> query) {
    return accessPage(dataset.path("access").path("userPages"), query, "members");
  }

  private ObjectNode accessPage(JsonNode pages, Map<String, String> query, String field) {
    int index =
        query.containsKey("cursor")
            ? Integer.parseInt(query.get("cursor").substring("page:".length()))
            : 0;
    ObjectNode body = json.createObjectNode().put("ok", true);
    if (pages.isArray() && index < pages.size()) body.set(field, pages.get(index));
    if (index + 1 < pages.size())
      body.putObject("response_metadata").put("next_cursor", "page:" + (index + 1));
    return body;
  }

  public String token() {
    return dataset.get("token").asString();
  }

  /** 데이터셋의 고정 시각(TS 생성기가 이 데이터셋을 수집한 시각). */
  public String now() {
    return dataset.get("now").asString();
  }

  /** 받은 요청의 경로 + 쿼리(요청 순서). */
  public List<String> requests() {
    return http.received().stream().map(Recorded::pathAndQuery).toList();
  }

  /** 받은 요청의 Authorization 헤더(요청 순서, 없으면 빈 문자열). */
  public List<String> authorizations() {
    return http.received().stream()
        .map(r -> Optional.ofNullable(r.header("Authorization")).orElse(""))
        .toList();
  }

  /** 미등록 경로·메서드 요청이 있었으면 실패(TESTING §4). 테스트 종료 시 부른다. */
  public void assertNoUnmatched() {
    http.assertNoUnmatched();
  }

  /** 경로 접두(예: "/api/conversations.history")에 대해 count번 장애 응답. */
  public void failNext(String pathPrefix, int count, Fault fault) {
    http.failNext(pathPrefix, count, response(fault));
  }

  /** 경로에 문자열이 포함된 첫 요청에서 latch가 열릴 때까지 블록(장애 테스트의 게이트). */
  public void gateOn(String pathContains, CountDownLatch latch, Consumer<String> onArrive) {
    http.gateOn(pathContains, latch, onArrive);
  }

  public synchronized void editMessage(
      String channelId, String ts, String newText, String editedTs) {
    for (JsonNode m : allMessages(channelId)) {
      if (ts.equals(m.path("ts").asString(""))) {
        ((ObjectNode) m).put("text", newText);
        ((ObjectNode) m)
            .putObject("edited")
            .put("user", m.path("user").asString())
            .put("ts", editedTs);
      }
    }
  }

  /** history 페이지와 스레드 replies 페이지 모두에서 지우고 지운 메시지 수를 돌려준다(ts가 ""이면 ts 없는 메시지). */
  public synchronized int deleteMessage(String channelId, String ts) {
    List<ArrayNode> lists = new ArrayList<>();
    for (JsonNode page : pages(channelId)) lists.add((ArrayNode) page.get("messages"));
    for (JsonNode th : dataset.get("history").get(channelId).get("threads"))
      for (JsonNode page : th.get("pages")) lists.add((ArrayNode) page.get("messages"));
    int deleted = 0;
    for (ArrayNode messages : lists)
      for (int i = messages.size() - 1; i >= 0; i--)
        if (ts.equals(messages.get(i).path("ts").asString(""))) {
          messages.remove(i);
          deleted++;
        }
    return deleted;
  }

  /**
   * 커서가 전진하지 않는 공급자: 경로(예: "/api/conversations.history")의 다음 응답들이 next_cursor로 첫 페이지를 다시 가리켜, 그 커서로
   * 온 요청에 같은 커서를 돌려준다. 그 뒤로는 데이터셋대로 답한다 — 가드가 없는 순회도 끝나므로 테스트가 시간 상한이 아니라 단언으로 실패한다.
   */
  public synchronized void repeatCursor(String path) {
    cursorRepeats.put(path, CURSOR_REPEATS);
  }

  /**
   * 커서가 두 값 사이를 돈다(A → B → A): 경로의 다음 응답 셋이 next_cursor로 A, B, A를 준다. A·B는 글자가 달라도 같은 첫 페이지를 가리킨다
   * ("page:0"·"page:00", 채널 목록은 "0"·"00"). 그 뒤로는 데이터셋대로 답해, 가드가 없는 순회도 끝난다.
   */
  public synchronized void cycleCursor(String path) {
    String a = firstPageCursor(path);
    String b = path.equals(CHANNELS) ? "00" : "page:00";
    cursorCycles.put(path, new ArrayDeque<>(List.of(a, b, a)));
  }

  /** 채널이 수집 중 사라진다: 참여 목록에는 남고 history는 channel_not_found로 답한다. */
  public synchronized void removeChannel(String channelId) {
    ((ObjectNode) dataset.get("history")).remove(channelId);
  }

  /** 스레드가 수집 중 사라진다: 루트 메시지(reply_count)는 history에 남고 replies는 thread_not_found로 답한다. */
  public synchronized void removeThread(String channelId, String threadTs) {
    ((ObjectNode) dataset.get("history").get(channelId).get("threads")).remove(threadTs);
  }

  public synchronized void addMessage(
      String channelId, int pageIndex, String user, String text, String ts) {
    ObjectNode m =
        json.createObjectNode()
            .put("type", "message")
            .put("user", user)
            .put("text", text)
            .put("ts", ts);
    ((ArrayNode) pages(channelId).get(pageIndex).get("messages")).add(m);
  }

  /** 스레드 replies 페이지 끝에 답글을 더한다. replyThreadTs는 답글이 가리키는 스레드(요청한 스레드와 다를 수 있다). */
  public synchronized void addReply(
      String channelId, String threadTs, int pageIndex, String ts, String replyThreadTs) {
    ObjectNode m =
        json.createObjectNode()
            .put("type", "message")
            .put("user", "U99")
            .put("text", "답글")
            .put("ts", ts)
            .put("thread_ts", replyThreadTs);
    JsonNode thread = dataset.get("history").get(channelId).get("threads").get(threadTs);
    ((ArrayNode) thread.get("pages").get(pageIndex).get("messages")).add(m);
  }

  private List<JsonNode> allMessages(String channelId) {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode page : pages(channelId)) page.get("messages").forEach(out::add);
    JsonNode threads = dataset.get("history").get(channelId).get("threads");
    for (JsonNode th : threads)
      for (JsonNode page : th.get("pages")) page.get("messages").forEach(out::add);
    return out;
  }

  private ArrayNode pages(String channelId) {
    return (ArrayNode) dataset.get("history").get(channelId).get("pages");
  }

  private ObjectNode channels(Map<String, String> q) {
    List<JsonNode> all = new ArrayList<>();
    for (JsonNode id : dataset.path("channels")) {
      boolean archived =
          dataset
              .path("access")
              .path("conversations")
              .path(id.asString())
              .path("is_archived")
              .asBoolean(false);
      if (!"true".equals(q.get("exclude_archived")) || !archived) all.add(id);
    }
    int size = dataset.get("conversationsPageSize").asInt();
    int from = q.get("cursor") == null ? 0 : Integer.parseInt(q.get("cursor"));
    ObjectNode body = json.createObjectNode().put("ok", true);
    ArrayNode channels = body.putArray("channels");
    for (int i = from; i < Math.min(all.size(), from + size); i++)
      channels.addObject().put("id", all.get(i).asString());
    if (from + size < all.size())
      body.putObject("response_metadata").put("next_cursor", String.valueOf(from + size));
    return body;
  }

  /** oldest(·inclusive)를 존중한다 — 실제 Slack처럼 범위 밖 메시지는 돌려주지 않고, 뒤 페이지에 남은 것이 없으면 next_cursor도 없다. */
  private ObjectNode page(JsonNode pages, String cursor, Map<String, String> q, String error) {
    if (error != null) return json.createObjectNode().put("ok", false).put("error", error);
    int index = cursor == null ? 0 : Integer.parseInt(cursor.substring("page:".length()));
    SlackTs oldest = q.get("oldest") == null ? null : new SlackTs(q.get("oldest"));
    boolean inclusive = "true".equals(q.get("inclusive"));
    ObjectNode body = json.createObjectNode().put("ok", true);
    ArrayNode out = body.putArray("messages");
    for (JsonNode m : pages.get(index).get("messages"))
      if (inRange(m, oldest, inclusive)) out.add(m.deepCopy());
    boolean more = false;
    for (int i = index + 1; i < pages.size(); i++)
      for (JsonNode m : pages.get(i).get("messages"))
        if (inRange(m, oldest, inclusive)) more = true;
    if (more && pages.get(index).has("nextCursor"))
      body.putObject("response_metadata")
          .put("next_cursor", pages.get(index).get("nextCursor").asString());
    return body;
  }

  private static boolean inRange(JsonNode m, SlackTs oldest, boolean inclusive) {
    if (oldest == null) return true;
    var ts =
        m.path("ts").isString() ? SlackTs.parse(m.get("ts").asString()) : Optional.<SlackTs>empty();
    if (ts.isEmpty()) return true; // ts 없는 항목은 그대로 흘려 보낸다(어댑터가 거른다)
    int cmp = SlackTs.CHRONOLOGICAL.compare(ts.get(), oldest);
    return inclusive ? cmp >= 0 : cmp > 0;
  }

  private interface Route {
    ObjectNode body(Map<String, String> query);
  }

  /** 데이터셋 읽기는 편집·삭제와 같은 잠금 아래에서. */
  private Response answer(String path, Route route, Recorded request) {
    ObjectNode body;
    synchronized (this) {
      body = route.body(request.params());
      int repeats = cursorRepeats.getOrDefault(path, 0);
      if (repeats > 0 && body.get("ok").asBoolean()) {
        cursorRepeats.put(path, repeats - 1);
        body.putObject("response_metadata").put("next_cursor", firstPageCursor(path));
      }
      Deque<String> cycle = cursorCycles.get(path);
      if (cycle != null && !cycle.isEmpty() && body.get("ok").asBoolean())
        body.putObject("response_metadata").put("next_cursor", cycle.removeFirst());
    }
    return jsonResponse(200, body);
  }

  /** 첫 페이지를 가리키는 커서: 채널 목록은 시작 인덱스, history·replies는 "page:0". */
  private static String firstPageCursor(String path) {
    return path.equals(CHANNELS) ? "0" : "page:0";
  }

  private ObjectNode history(Map<String, String> q) {
    String channel = q.get("channel");
    return page(
        dataset.get("history").path(channel).path("pages"),
        q.get("cursor"),
        q,
        channel == null || !dataset.get("history").has(channel) ? "channel_not_found" : null);
  }

  private ObjectNode replies(Map<String, String> q) {
    JsonNode th = dataset.get("history").path(q.get("channel")).path("threads").path(q.get("ts"));
    return page(
        th.path("pages"), q.get("cursor"), q, th.isMissingNode() ? "thread_not_found" : null);
  }

  private Response response(Fault fault) {
    if (fault.rawBody() != null) {
      return new Response(fault.status(), fault.rawBody(), Map.of("Content-Type", "text/html"));
    }
    Response failure = apiFailure(fault.status(), fault.error());
    if (fault.retryAfterSeconds() == null) return failure;
    return new Response(
        failure.status(),
        failure.body(),
        Map.of(JSON_CONTENT_TYPE, JSON_MEDIA_TYPE, "Retry-After", fault.retryAfterSeconds()));
  }

  private Response apiFailure(int status, String error) {
    return jsonResponse(status, json.createObjectNode().put("ok", false).put("error", error));
  }

  private Response jsonResponse(int status, JsonNode body) {
    return new Response(
        status, json.writeValueAsString(body), Map.of(JSON_CONTENT_TYPE, JSON_MEDIA_TYPE));
  }

  /** 미등록 요청을 검사하고 닫는다. */
  @Override
  public void close() {
    http.close();
  }

  /** 검사 없이 닫는다 — 가짜 자체를 시험하는 테스트만. */
  public void abandon() {
    http.abandon();
  }
}
