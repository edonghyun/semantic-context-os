package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 가짜 Jira 자체 테스트(F15 C7): 잘못된 요청을 숨기지 않고(TESTING §4: 데이터셋에 없는 쿼리와 틀린 자격 증명은 그대로 드러난다), 각본
 * 어휘(respond 덮어쓰기, TS 장애 사례 failAlways, 검색 누락 omitFromIssueSearch)가 말한 요청에만 걸린다.
 */
@DisplayName("가짜 Jira는 데이터셋대로 답하고 잘못된 요청을 드러낸다(C7)")
class FakeJiraServerTest {
  static final String DASHBOARDS = "/rest/api/3/dashboard";

  final FakeJiraServer jira = newServer();

  static FakeJiraServer newServer() {
    try {
      return new FakeJiraServer(JiraFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterEach
  void stop() {
    jira.abandon();
  }

  int send(String pathAndQuery, String authorization) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(jira.baseUrl() + pathAndQuery))
            .header("Authorization", authorization)
            .build();
    return HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  @DisplayName("데이터셋에 없는 쿼리는 미등록으로 남아 검사에서 실패한다")
  void failsOnUnknownQuery() throws Exception {
    // when
    send(DASHBOARDS + "?startAt=7", jira.basicAuthorization());

    // then
    assertThatThrownBy(jira::assertNoUnmatched).hasMessageContaining(DASHBOARDS + "?startAt=7");
  }

  @Test
  @DisplayName("데이터셋 자격 증명이 아니면 401로 답한다")
  void rejectsWrongCredential() throws Exception {
    // when
    int status = send(DASHBOARDS + "?maxResults=50&startAt=0", "Basic d3Jvbmc=");

    // then
    assertThat(status).isEqualTo(401);
  }

  static final String DASHBOARDS_FIRST_PAGE = DASHBOARDS + "?maxResults=50&startAt=0";
  static final String FIELDS = "/rest/api/3/field";

  HttpResponse<Void> exchange(String pathAndQuery) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(jira.baseUrl() + pathAndQuery))
            .header("Authorization", jira.basicAuthorization())
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
  }

  List<Integer> statusesOf(String... pathAndQueries) throws Exception {
    List<Integer> statuses = new ArrayList<>();
    for (String p : pathAndQueries) statuses.add(exchange(p).statusCode());
    return statuses;
  }

  @Test
  @DisplayName("respond로 덮은 경로는 쿼리와 무관하게 그 응답이다")
  void respondOverridesAnyQuery() throws Exception {
    // given
    jira.respond(DASHBOARDS, new Response(418, "{}", Map.of()));

    // when
    List<Integer> statuses = statusesOf(DASHBOARDS_FIRST_PAGE, DASHBOARDS + "?startAt=7");

    // then
    assertThat(statuses).containsExactly(418, 418);
  }

  @Nested
  @DisplayName("TS 장애 사례(필드 목록이 계속 429)를 걸면")
  class FailAlways {
    @BeforeEach
    void givenFieldsAlwaysRateLimited() {
      jira.failAlways(
          JiraFixtures.JSON.readTree(
              """
              {"name": "필드 목록이 계속 429", "pathPrefix": "/rest/api/3/field",
               "status": 429, "headers": {"retry-after": "1"}}
              """));
    }

    @Test
    @DisplayName("그 접두의 요청은 몇 번이든 그 상태로 답한다")
    void answersStatusEveryTime() throws Exception {
      // when
      List<Integer> statuses = statusesOf(FIELDS, FIELDS, FIELDS);

      // then
      assertThat(statuses).containsExactly(429, 429, 429);
    }

    @Test
    @DisplayName("사례의 헤더(retry-after)를 함께 보낸다")
    void sendsCaseHeaders() throws Exception {
      // when
      HttpResponse<Void> response = exchange(FIELDS);

      // then
      assertThat(response.headers().firstValue("retry-after")).contains("1");
    }

    @Test
    @DisplayName("대조군: 다른 접두의 요청은 데이터셋으로 답한다")
    void otherPrefixServesDataset() throws Exception {
      // when
      int status = exchange(DASHBOARDS_FIRST_PAGE).statusCode();

      // then
      assertThat(status).isEqualTo(200);
    }
  }

  static final String SEARCH = "/rest/api/3/search/jql";

  /** 데이터셋 첫 프로젝트(10000) 이슈 검색 첫 페이지의 쿼리. */
  static final String FIRST_PAGE =
      SEARCH + "?maxResults=50&jql=project+%3D+10000+ORDER+BY+id+ASC&fields=summary";

  /** 이슈 검색 응답의 이슈 id(응답 순서). */
  List<String> searchedIds(String pathAndQuery) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(jira.baseUrl() + pathAndQuery))
            .header("Authorization", jira.basicAuthorization())
            .build();
    JsonNode body =
        JiraFixtures.JSON.readTree(
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
    List<String> ids = new ArrayList<>();
    body.get("issues").forEach(i -> ids.add(i.get("id").asString()));
    return ids;
  }

  @Test
  @DisplayName("대조군: 빼지 않으면 첫 검색 페이지는 40001·40002·40003·40006·40007이다")
  void searchServesDataset() throws Exception {
    // when
    List<String> ids = searchedIds(FIRST_PAGE);

    // then
    assertThat(ids).containsExactly("40001", "40002", "40003", "40006", "40007");
  }

  @Nested
  @DisplayName("이슈 검색에서 40002를 빼면")
  class OmitFromIssueSearch {
    @BeforeEach
    void givenOmitted() {
      jira.omitFromIssueSearch("40002");
    }

    @Test
    @DisplayName("그 이슈만 빠지고 나머지는 순서대로 남는다")
    void dropsOnlyThatIssue() throws Exception {
      // when
      List<String> ids = searchedIds(FIRST_PAGE);

      // then
      assertThat(ids).containsExactly("40001", "40003", "40006", "40007");
    }

    @Test
    @DisplayName("다음 검색 페이지는 그대로다")
    void otherPageIsUntouched() throws Exception {
      // when
      List<String> ids = searchedIds(FIRST_PAGE + "&nextPageToken=tok-page-2");

      // then
      assertThat(ids).containsExactly("40004", "40005");
    }
  }
}
