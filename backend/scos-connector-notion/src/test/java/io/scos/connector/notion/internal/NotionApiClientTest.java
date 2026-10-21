package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.notion.internal.NotionClientScenario.FailureView;
import io.scos.connector.notion.internal.NotionClientScenario.Operation;
import io.scos.connector.notion.internal.NotionClientScenario.Outcome;
import io.scos.spi.connector.CallFailure;
import io.scos.support.FakeHttpServer.Recorded;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Notion API 클라이언트(TS NotionApiClient·classifyNotionError, TESTING §3.6 2·3층): 상태·본문·Retry-After의
 * 분류, 단건·목록 응답 검증, 요청 모양. 기대값은 TS가 만든 표(expected-client.json)다.
 */
@DisplayName("Notion API 클라이언트는 TS NotionApiClient·classifyNotionError와 같다")
class NotionApiClientTest {
  static final JsonNode TABLE = NotionFixtures.client();

  /** 2xx 바로 위 상태 — Java는 리다이렉트를 따르지 않는다(CONTRACTS F17-D3). */
  static final int REDIRECT = 300;

  /** 결과 없는 마지막 검색 페이지. */
  static final String EMPTY_SEARCH =
      "{\"object\":\"list\",\"results\":[],\"next_cursor\":null,\"has_more\":false,"
          + "\"type\":\"page_or_data_source\",\"page_or_data_source\":{}}";

  final NotionClientScenario notion = new NotionClientScenario();

  @AfterEach
  void close() {
    notion.close();
  }

  static Stream<Arguments> rows(String table) {
    return StreamSupport.stream(TABLE.get(table).spliterator(), false)
        .map(row -> Arguments.of(row.get("label").asString(), row));
  }

  @Nested
  @DisplayName("TS 표의 응답으로 답하면")
  class TsTable {
    static Stream<Arguments> statusCases() {
      return rows("statusCases");
    }

    static Stream<Arguments> retrieveCases() {
      return rows("retrieveCases");
    }

    static Stream<Arguments> listCases() {
      return rows("listCases");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statusCases")
    @DisplayName("응답 상태·본문·Retry-After는 TS와 같은 결과로 분류된다")
    void classifiesLikeTs(String label, JsonNode row) {
      // given
      notion.answers(
          Operation.PAGE,
          row.get("status").asInt(),
          row.get("body").asString(),
          NotionClientScenario.headersOf(row));

      // when
      Outcome outcome = notion.outcome(Operation.PAGE);

      // then
      assertThat(outcome).isEqualTo(Outcome.expected(row.get("outcome")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retrieveCases")
    @DisplayName("단건 응답의 모양 검사가 TS와 같다")
    void checksObjectShapeLikeTs(String label, JsonNode row) {
      // given
      Operation operation = Operation.of(row.get("operation").asString());
      notion.answersOk(operation, row.get("value"));

      // when
      Outcome outcome = notion.outcome(operation);

      // then
      assertThat(outcome).isEqualTo(Outcome.expected(row.get("outcome")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listCases")
    @DisplayName("목록 응답의 봉투·항목·커서 검사가 TS와 같다")
    void checksListShapeLikeTs(String label, JsonNode row) {
      // given
      Operation operation = Operation.of(row.get("operation").asString());
      notion.answersOk(operation, row.get("value"));

      // when
      Outcome outcome = notion.outcome(operation);

      // then
      assertThat(outcome).isEqualTo(Outcome.expected(row.get("outcome")));
    }
  }

  @Nested
  @DisplayName("HTTP 응답 없이 실패하면")
  class TransportFailure {
    @Test
    @DisplayName("재시도하는 의존 장애다")
    void transportFailureIsRetriedDependencyFailure() {
      // when
      CallFailure failure = NotionClientScenario.transportFailure();

      // then
      FailureView ts = FailureView.expected(TABLE.get("transportFailure"));
      assertThat(failure)
          .extracting(f -> f.kind().wire(), CallFailure::retryable)
          .containsExactly(ts.kind(), ts.retryable());
    }

    @Test
    @DisplayName("메시지 없는 전송 실패는 TS 기본 문구로 분류한다")
    void transportFailureWithoutMessage() {
      // when
      CallFailure failure = NotionErrors.classify(new IllegalStateException());

      // then
      assertThat(failure.message()).isEqualTo("Notion request failed");
    }
  }

  @Nested
  @DisplayName("데이터셋 응답으로 조회하면")
  class Requests {
    @Test
    @DisplayName("Bearer 토큰을 보낸다")
    void sendsAuthorization() {
      // when
      notion.read(c -> c.retrievePage(notion.token(), "root-wiki", NotionClientScenario.TIMEOUT));

      // then
      assertThat(notion.requests().getFirst().header("Authorization"))
          .isEqualTo(TABLE.get("authorization").asString());
    }

    @Test
    @DisplayName("데이터셋 Notion-Version 헤더를 보낸다")
    void sendsVersion() {
      // when
      notion.read(c -> c.retrievePage(notion.token(), "root-wiki", NotionClientScenario.TIMEOUT));

      // then
      assertThat(notion.requests().getFirst().header("Notion-Version"))
          .isEqualTo(TABLE.get("apiVersion").asString());
    }

    @Test
    @DisplayName("휴지통 검색 본문은 TS JSON.stringify와 같다")
    void trashSearchBodyMatchesTs() {
      // when
      notion.read(
          c ->
              c.searchPages(
                  notion.token(),
                  Optional.empty(),
                  SearchPhase.TRASH,
                  NotionClientScenario.TIMEOUT));

      // then
      assertThat(notion.requests().getFirst().body())
          .isEqualTo(
              "{\"filter\":{\"property\":\"object\",\"value\":\"page\",\"in_trash\":true},"
                  + "\"sort\":{\"direction\":\"descending\",\"timestamp\":\"last_edited_time\"},"
                  + "\"page_size\":100}");
    }

    @Test
    @DisplayName("다음 페이지 검색은 본문 끝에 start_cursor를 둔다")
    void searchCursorGoesLast() {
      // when
      notion.read(
          c ->
              c.searchPages(
                  notion.token(),
                  Optional.of("cur-search-2"),
                  SearchPhase.ACTIVE,
                  NotionClientScenario.TIMEOUT));

      // then
      assertThat(notion.requests().getFirst().body())
          .endsWith(",\"page_size\":100,\"start_cursor\":\"cur-search-2\"}");
    }

    @Test
    @DisplayName("블록 커서는 쿼리 start_cursor로 보낸다")
    void blockCursorIsQueryParameter() {
      // when
      notion.read(
          c ->
              c.listBlockChildren(
                  notion.token(),
                  "root-wiki",
                  Optional.of("cur-wiki-2"),
                  NotionClientScenario.TIMEOUT));

      // then
      assertThat(notion.requests().getFirst().params())
          .isEqualTo(Map.of("page_size", "100", "start_cursor", "cur-wiki-2"));
    }

    @Test
    @DisplayName("data source 조회 본문은 page_size와 start_cursor뿐이다")
    void queryBodyHasOnlyPaging() {
      // when
      notion.read(
          c ->
              c.queryDataSource(
                  notion.token(),
                  "ds-tasks",
                  Optional.of("cur-rows-2"),
                  NotionClientScenario.TIMEOUT));

      // then
      assertThat(notion.requests().getFirst().body())
          .isEqualTo("{\"page_size\":100,\"start_cursor\":\"cur-rows-2\"}");
    }

    @Test
    @DisplayName("객체는 응답 원문의 키 순서 그대로 돌려준다")
    void keepsResponseKeyOrder() {
      // when
      JsonNode page =
          notion.read(
              c -> c.retrievePage(notion.token(), "root-wiki", NotionClientScenario.TIMEOUT));

      // then
      assertThat(page.get("properties").propertyNames())
          .containsExactly("title", "2026", "분류", "10");
    }

    @Test
    @DisplayName("API origin 끝의 /를 뗀 경로로 부른다")
    void dropsTrailingSlashOfOrigin() {
      // when
      notion.readWithTrailingSlash(
          c -> c.retrievePage(notion.token(), "root-wiki", NotionClientScenario.TIMEOUT));

      // then
      assertThat(notion.requests().getFirst().path()).isEqualTo(NotionClientScenario.ROOT_PAGE);
    }
  }

  @Nested
  @DisplayName("응답 상태가 2xx·3xx 경계에 있으면")
  class StatusBounds {
    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {200, 299})
    @DisplayName("2xx 경계는 성공이다")
    void acceptsSuccessBounds(int status) {
      // given
      notion.answers(Operation.SEARCH, status, EMPTY_SEARCH, Map.of());

      // when
      Outcome outcome = notion.outcome(Operation.SEARCH);

      // then
      assertThat(outcome.ok()).isTrue();
    }

    @Test
    @DisplayName("3xx는 본문이 목록이어도 실패다")
    void rejectsRedirectStatus() {
      // given
      notion.answers(Operation.SEARCH, REDIRECT, EMPTY_SEARCH, Map.of());

      // when
      Outcome outcome = notion.outcome(Operation.SEARCH);

      // then
      assertThat(outcome.ok()).isFalse();
    }
  }

  @Nested
  @DisplayName("루트 페이지가 따라가면 성공하는 리다이렉트로 답하면")
  class RedirectAnswer {
    @Test
    @DisplayName("Location으로 요청을 보내지 않는다(토큰이 새지 않는다)")
    void doesNotFollowRedirect() {
      // given
      String target = notion.rootPageRedirectsToValidPage();

      // when
      notion.outcome(Operation.PAGE);

      // then: 루트 페이지는 요청했다(아니면 given 준비 실패)
      notion.requireRequested(NotionClientScenario.ROOT_PAGE);
      assertThat(notion.requests()).extracting(Recorded::path).doesNotContain(target);
    }
  }

  @Nested
  @DisplayName("경로의 id를 인코딩하면")
  class PathSegment {
    @Test
    @DisplayName("encodeURIComponent처럼 인코딩한다")
    void encodesIdLikeEncodeUriComponent() {
      // when
      String segment = NotionApiClient.segment("a b/c?d#é!~*'()");

      // then
      assertThat(segment).isEqualTo("a%20b%2Fc%3Fd%23%C3%A9!~*'()");
    }
  }
}
