package io.scos.connector.notion.internal;

import io.scos.engine.support.call.ProviderHttp;
import io.scos.engine.support.call.RetryAfter;
import io.scos.engine.support.text.JsJson;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.http.HttpHeaders;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Notion API 클라이언트(TS NotionApiClient, notion-api-client.ts): 시도 하나의 교환만 한다 — 재시도·대기는 {@code
 * ProviderCallExecutor}의 몫이다. 교환은 {@link ProviderHttp}(ADR-0022 §4). `Authorization: Bearer
 * <token>`과 `Notion-Version: 2026-03-11`을 보내고, POST(검색·data source 조회)는 TS `JSON.stringify`와 같은 본문을
 * 보낸다.
 *
 * <p>응답은 상태와 무관하게 먼저 JSON으로 읽는다(TS `response.json()`): 읽을 수 없으면(빈 본문·HTML 게이트웨이 오류 포함) 상태와 무관하게
 * {@code invalid_provider_response}(비재시도)다. 2xx가 아니면 본문 code(문자열이 아니면 `http_error`)와 Retry-After(초
 * — {@link RetryAfter#seconds})로 {@link NotionApiException}, 2xx인데 JSON 객체가 아니면 {@code
 * invalid_provider_response}다. 목록은 봉투(object·type·has_more·results·next_cursor)와 항목 모양을 검사하고, 불완전한
 * 결과 (request_status.incomplete_reason)는 실패다. 객체는 원문 그대로 돌려준다(원본 캡처가 응답 순서를 쓴다). 스레드 안전하다.
 */
public final class NotionApiClient {
  public static final String API_VERSION = "2026-03-11";
  static final int PAGE_SIZE = 100;

  /** 검색 호출 이름(지표 operation) — roots discovery와 검색 모드가 같이 쓴다. */
  static final String SEARCH_OPERATION = "notion.search.pages";

  /** 목록 한 페이지: 원문 항목과 다음 커서(마지막 페이지면 없음). */
  record ListPage(List<JsonNode> results, Optional<String> nextCursor) {}

  private static final String NOTION_VERSION = "Notion-Version";
  private static final String HTTP_ERROR = "http_error";
  private static final String SEARCH_TYPE = "page_or_data_source";
  private static final String BLOCK_TYPE = "block";

  private final String baseUrl;
  private final ProviderHttp http;
  private final ObjectMapper json;

  /**
   * 제품 HTTP 클라이언트: 리다이렉트를 따르지 않는다(Redirect.NEVER 명시) — Bearer 토큰을 실은 요청이 Location의 다른 호스트로 가지 않게.
   * 3xx는 실패 상태로 남아 본문으로 분류된다(CONTRACTS F17-D3). 운영 빈과 테스트 조립이 이것 하나를 쓴다.
   */
  public static HttpClient httpClient() {
    return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** baseUrl: API origin(운영 `https://api.notion.com`, 테스트는 가짜 서버). */
  public NotionApiClient(String baseUrl, ProviderHttp http, ObjectMapper json) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = http;
    this.json = json;
  }

  /** 페이지 검색(POST /v1/search): 마지막 편집 시각 내림차순, 휴지통 단계면 in_trash 필터. 결과는 페이지만 받는다. */
  ListPage searchPages(String token, Optional<String> cursor, SearchPhase phase, Duration timeout) {
    ObjectNode filter = JsonNodeFactory.instance.objectNode().put("property", "object");
    filter.put("value", NotionObjects.PAGE);
    if (phase == SearchPhase.TRASH) filter.put("in_trash", true);
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.set("filter", filter);
    body.set(
        "sort",
        JsonNodeFactory.instance
            .objectNode()
            .put("direction", "descending")
            .put("timestamp", "last_edited_time"));
    body.put("page_size", PAGE_SIZE);
    cursor.ifPresent(c -> body.put("start_cursor", c));
    JsonNode response = post("/v1/search", token, body, timeout);
    return list(response, SEARCH_TYPE, NotionObjects::isPage, "incomplete_search_results");
  }

  /** 블록 자식 한 페이지(GET /v1/blocks/{id}/children). */
  ListPage listBlockChildren(
      String token, String blockId, Optional<String> cursor, Duration timeout) {
    String query =
        "?page_size="
            + PAGE_SIZE
            + cursor
                .map(c -> "&start_cursor=" + URLEncoder.encode(c, StandardCharsets.UTF_8))
                .orElse("");
    JsonNode response = get("/v1/blocks/" + segment(blockId) + "/children" + query, token, timeout);
    return list(response, BLOCK_TYPE, NotionObjects::isBlock, "incomplete_search_results");
  }

  JsonNode retrievePage(String token, String pageId, Duration timeout) {
    return object(get("/v1/pages/" + segment(pageId), token, timeout), NotionObjects::isPage);
  }

  JsonNode retrieveDatabase(String token, String databaseId, Duration timeout) {
    return object(
        get("/v1/databases/" + segment(databaseId), token, timeout), NotionObjects::isDatabase);
  }

  JsonNode retrieveDataSource(String token, String dataSourceId, Duration timeout) {
    return object(
        get("/v1/data_sources/" + segment(dataSourceId), token, timeout),
        NotionObjects::isDataSource);
  }

  /**
   * data source 행 한 페이지(POST /v1/data_sources/{id}/query). 결과는 행 페이지와 중첩 data source다. in_trash 필터는
   * 보내지 않는다 — 이 끝점은 그 필드를 400 validation_error로 거부한다(TS 주석, API 2026-03-11).
   */
  ListPage queryDataSource(
      String token, String dataSourceId, Optional<String> cursor, Duration timeout) {
    ObjectNode body = JsonNodeFactory.instance.objectNode().put("page_size", PAGE_SIZE);
    cursor.ifPresent(c -> body.put("start_cursor", c));
    JsonNode response =
        post("/v1/data_sources/" + segment(dataSourceId) + "/query", token, body, timeout);
    return list(
        response, SEARCH_TYPE, NotionObjects::isDataSourceEntry, "incomplete_query_results");
  }

  private JsonNode get(String pathAndQuery, String token, Duration timeout) {
    ProviderHttp.Response response =
        http.get(URI.create(baseUrl + pathAndQuery), headers(token), timeout);
    return body(response);
  }

  private JsonNode post(String path, String token, ObjectNode body, Duration timeout) {
    ProviderHttp.Response response =
        http.postJson(URI.create(baseUrl + path), headers(token), JsJson.stringify(body), timeout);
    return body(response);
  }

  private static Map<String, String> headers(String token) {
    return Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token, NOTION_VERSION, API_VERSION);
  }

  /** TS request(): 본문을 먼저 읽고, 실패 상태면 code·Retry-After, 성공이면 객체여야 한다. */
  private JsonNode body(ProviderHttp.Response response) {
    JsonNode body = parse(response.body());
    if (response.status() < 200 || response.status() > 299) {
      JsonNode code = body.isObject() ? body.get("code") : null;
      throw new NotionApiException(
          code != null && code.isString() ? code.asString() : HTTP_ERROR,
          RetryAfter.seconds(response.headers().getFirst(HttpHeaders.RETRY_AFTER)));
    }
    if (!body.isObject()) throw NotionApiException.invalidResponse();
    return body;
  }

  /** JSON 한 값. 빈 본문·공백뿐인 본문은 Jackson이 예외 없이 missing으로 읽는다 — TS JSON.parse처럼 실패로 본다. */
  private JsonNode parse(String text) {
    JsonNode body;
    try {
      body = json.readTree(text);
    } catch (JacksonException e) {
      throw NotionApiException.invalidResponse();
    }
    if (body.isMissingNode()) throw NotionApiException.invalidResponse();
    return body;
  }

  private static JsonNode object(JsonNode body, Predicate<JsonNode> shape) {
    if (!shape.test(body)) throw NotionApiException.invalidResponse();
    return body;
  }

  /** TS readListResponse: 봉투·항목 모양, 불완전한 결과, has_more와 next_cursor의 짝. */
  private static ListPage list(
      JsonNode body, String type, Predicate<JsonNode> item, String incompleteCode) {
    JsonNode results = body.get("results");
    if (!text(body.get("object"), "list")
        || !text(body.get("type"), type)
        || body.get("has_more") == null
        || !body.get("has_more").isBoolean()
        || results == null
        || !results.isArray()) throw NotionApiException.invalidResponse();
    List<JsonNode> items = new ArrayList<>();
    for (JsonNode result : results) {
      if (!item.test(result)) throw NotionApiException.invalidResponse();
      items.add(result);
    }
    JsonNode status = body.get("request_status");
    if (status != null
        && status.isObject()
        && status.get("incomplete_reason") != null
        && status.get("incomplete_reason").isString())
      throw new NotionApiException(incompleteCode, Optional.empty());
    boolean hasMore = body.get("has_more").asBoolean();
    JsonNode next = body.get("next_cursor");
    // JS: undefined(필드 없음)는 null이 아니다 — has_more가 false여도 next_cursor는 null로 와야 한다
    boolean isNull = next != null && next.isNull();
    boolean isString = next != null && next.isString();
    if ((hasMore && !NotionObjects.nonEmptyString(next))
        || (!hasMore && !isNull)
        || (!isNull && !isString)) throw NotionApiException.invalidResponse();
    return new ListPage(
        List.copyOf(items), isString ? Optional.of(next.asString()) : Optional.empty());
  }

  private static boolean text(JsonNode value, String expected) {
    return value != null && value.isString() && value.asString().equals(expected);
  }

  /** 경로 조각 하나(TS encodeURIComponent: 영숫자와 -_.!~*'() 밖은 UTF-8 퍼센트 인코딩). */
  static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~");
  }
}
