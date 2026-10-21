package io.scos.connector.tiro.internal;

import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 가짜 Tiro REST API(합성 데이터). harness/fixtures/tiro/tiro-dataset.json을 TS make-tiro-fixture.ts의
 * fakeFetch와 같은 규칙으로 서빙한다: 커서 "page:<다음 인덱스>", 노트 자원의 `{status}`는 그 상태 응답, 목록에 있는데 데이터셋에 없는
 * 노트(n-gone)의 상세는 404, 폴더가 다르면 400. 공용 {@link FakeHttpServer} 위의 얇은 층이다: 이 클래스는 데이터셋이 가진 GET
 * 경로(목록·노트 상세·문단·요약 목록·상세·문서 목록·상세)의 응답, 키가 다를 때의 401, Tiro 형식의 고장 몸체만 정한다. 미등록 요청 기록·장애 각본
 * 횟수·게이트·요청 기록은 공용 기반의 것이다. 실제 Tiro로는 절대 나가지 않는다.
 */
public final class FakeTiroServer implements AutoCloseable {
  /** 장애 응답: 상태 코드, Retry-After 헤더(없으면 없음), 본문(JSON이 아닐 수 있다). */
  public record Fault(int status, Optional<String> retryAfter, String body) {
    /** Tiro 오류 형식 본문 — 본문의 비밀 문자열이 실패 메시지로 새지 않는지 볼 수 있게. */
    public static Fault status(int status) {
      return new Fault(
          status, Optional.empty(), "{\"error\":{\"message\":\"private transcript secret\"}}");
    }

    public static Fault rateLimited(String retryAfter) {
      return new Fault(429, Optional.of(retryAfter), "{}");
    }

    /** 성공 상태인데 본문이 JSON이 아니다(LB 오류 페이지 등). */
    public static Fault nonJson(int status) {
      return new Fault(status, Optional.empty(), "<html>Bad Gateway</html>");
    }

    Response response() {
      return new Response(
          status, body, retryAfter.map(r -> Map.of("Retry-After", r)).orElse(Map.of()));
    }
  }

  private static final String NOTES = "/v1/external/notes/";
  private static final List<String> RESOURCES = List.of("paragraphs", "summaries", "documents");

  private final ObjectMapper json = new ObjectMapper();
  private final ObjectNode dataset;
  private final FakeHttpServer http;
  private final Set<String> removed = ConcurrentHashMap.newKeySet();
  private final Map<String, Fault> cursorFaults = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> cursorFaultBudget = new ConcurrentHashMap<>();

  public FakeTiroServer(Path datasetFile) throws IOException {
    this.dataset = (ObjectNode) json.readTree(Files.readString(datasetFile));
    this.http = new FakeHttpServer();
    http.requireHeader("Authorization", "Bearer " + token(), FakeHttpServer.status(401, "{}"));
    http.on("GET", listPath(), this::listing);
    Set<String> listed = new HashSet<>();
    for (JsonNode page : dataset.get("listPages"))
      for (JsonNode n : page.get("content")) listed.add(n.get("guid").asString());
    for (String guid : listed) {
      JsonNode note = dataset.get("notes").get(guid);
      if (note == null) {
        http.on("GET", NOTES + guid, r -> FakeHttpServer.status(404, "{}"));
        continue;
      }
      JsonNode detail = note.get("detail");
      if (detail.has("status")) {
        // 상세가 이 상태로 답한다(n-locked 403) — 자원 경로는 부르지 않으므로 등록하지 않는다
        http.on(
            "GET", NOTES + guid, r -> FakeHttpServer.status(detail.get("status").asInt(), "{}"));
        continue;
      }
      if (detail.has("rawJson")) {
        // 원문 본문 — 키 순서·수 표기를 응답 그대로(데이터셋 파일의 JSON.stringify 순서가 아니라)
        String raw = detail.get("rawJson").asString();
        http.on(
            "GET",
            NOTES + guid,
            r -> new Response(200, raw, Map.of("Content-Type", "application/json")));
      } else http.on("GET", NOTES + guid, r -> ok(detail));
      for (String resource : RESOURCES) registerResource(guid, resource);
    }
  }

  private void registerResource(String guid, String resource) {
    String path = NOTES + guid + "/" + resource;
    http.on("GET", path, r -> resourceAnswer(path, guid, resource, Optional.empty(), r));
    JsonNode res = dataset.get("notes").get(guid).get(resource);
    JsonNode details = res.get("details");
    if (details == null) return;
    for (String id : details.propertyNames())
      http.on(
          "GET",
          path + "/" + id,
          r -> resourceAnswer(path + "/" + id, guid, resource, Optional.of(id), r));
    // 목록에는 있는데 상세가 데이터셋에 없는 항목(n-delta 요약 41)은 404 — 데이터셋이 선언한 경로다
    for (JsonNode entry : res.get("listing").get("content")) {
      String id = entry.get("id").asString();
      if (!details.has(id)) http.on("GET", path + "/" + id, r -> FakeHttpServer.status(404, "{}"));
    }
  }

  public String baseUrl() {
    return http.baseUrl();
  }

  public String token() {
    return dataset.get("token").asString();
  }

  /** 데이터셋의 수집 설정(workspaceGuid·folderId) JSON. */
  public String configJson() {
    return json.writeValueAsString(dataset.get("config"));
  }

  /** 받은 요청의 경로 + 쿼리(디코드, 요청 순서). */
  public List<String> requests() {
    return http.received().stream().map(Recorded::pathAndQuery).toList();
  }

  /** 받은 요청의 한 헤더(요청 순서, 없으면 빈 문자열). */
  public List<String> headers(String name) {
    return http.received().stream()
        .map(r -> Optional.ofNullable(r.header(name)).orElse(""))
        .toList();
  }

  /** 미등록 경로·메서드 요청이 있었으면 실패(TESTING §4). 테스트 종료 시 부른다. */
  public void assertNoUnmatched() {
    http.assertNoUnmatched();
  }

  /** 경로 접두의 다음 요청 count번이 이 장애로 답한다. */
  public void failNext(String pathPrefix, int count, Fault fault) {
    http.failNext(pathPrefix, count, fault.response());
  }

  /** 경로가 path이고 쿼리 커서가 cursor인 다음 요청 count번이 이 장애로 답한다(둘째 페이지만 실패). */
  public void failOnCursor(String path, String cursor, int count, Fault fault) {
    cursorFaults.put(path + "?" + cursor, fault);
    cursorFaultBudget.put(path + "?" + cursor, new AtomicInteger(count));
  }

  /** 경로에 데이터셋 대신 이 JSON 본문으로 답한다(데이터셋에 없는 경로면 그 경로를 등록한다 — 형식 위반·추가 자원 응답). */
  public void answer(String path, String body) {
    http.on("GET", path, r -> FakeHttpServer.ok(body));
  }

  /** 목록에서 노트를 뺀다(상세는 그대로 — 폴더 이동·권한 회수와 같다). */
  public void removeFromListing(String guid) {
    removed.add(guid);
  }

  /** 노트 문단 첫 페이지 첫 문단의 전사 본문을 바꾼다. */
  public void editFirstTranscript(String guid, String content) {
    editTranscript(guid, 0, content);
  }

  /** 노트 문단 첫 페이지의 index번째 문단 전사 본문을 바꾼다. */
  public synchronized void editTranscript(String guid, int index, String content) {
    ObjectNode paragraph =
        (ObjectNode)
            dataset
                .get("notes")
                .get(guid)
                .get("paragraphs")
                .get("pages")
                .get(0)
                .get("content")
                .get(index);
    paragraph.putObject("transcript").put("type", "text/plain").put("content", content);
  }

  /** 경로+쿼리에 문자열이 포함된 첫 요청을 latch가 열릴 때까지 멈춘다(kill 테스트의 게이트). */
  public void gateOn(String pathContains, CountDownLatch latch, Consumer<String> onArrive) {
    http.gateOn(pathContains, latch, onArrive);
  }

  private String listPath() {
    return "/v1/external/workspaces/"
        + dataset.get("config").get("workspaceGuid").asString()
        + "/notes";
  }

  private Optional<Response> cursorFault(String path, Recorded r) {
    String key = path + "?" + r.params().get("cursor");
    AtomicInteger budget = cursorFaultBudget.get(key);
    if (budget == null || budget.getAndDecrement() <= 0) return Optional.empty();
    return Optional.of(cursorFaults.get(key).response());
  }

  /** 폴더 없이 요청하면(allNotes) 데이터셋 전체가 그 워크스페이스의 노트다. */
  private synchronized Response listing(Recorded r) {
    Optional<Response> fault = cursorFault(listPath(), r);
    if (fault.isPresent()) return fault.get();
    String folder = r.params().get("folderId");
    if (folder != null && !folder.equals(dataset.get("config").get("folderId").asString()))
      return FakeHttpServer.status(400, "{}");
    JsonNode page = dataset.get("listPages").get(pageIndex(r.params().get("cursor")));
    ObjectNode body = json.createObjectNode();
    ArrayNode content = body.putArray("content");
    for (JsonNode n : page.get("content"))
      if (!removed.contains(n.get("guid").asString())) content.add(n.deepCopy());
    body.set("nextCursor", page.get("nextCursor").deepCopy());
    return ok(body);
  }

  private synchronized Response resourceAnswer(
      String path, String guid, String resource, Optional<String> id, Recorded r) {
    Optional<Response> fault = cursorFault(path, r);
    if (fault.isPresent()) return fault.get();
    JsonNode res = dataset.get("notes").get(guid).get(resource);
    if (res.has("status")) return FakeHttpServer.status(res.get("status").asInt(), "{}");
    if (res.has("pages")) return ok(res.get("pages").get(pageIndex(r.params().get("cursor"))));
    return ok(id.map(i -> res.get("details").get(i)).orElse(res.get("listing")));
  }

  private Response ok(JsonNode body) {
    return new Response(
        200, json.writeValueAsString(body), Map.of("Content-Type", "application/json"));
  }

  private static int pageIndex(String cursor) {
    return cursor == null ? 0 : Integer.parseInt(cursor.substring("page:".length()));
  }

  @Override
  public void close() {
    http.close();
  }
}
