package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.connector.jira.internal.JiraPages.Continuation;
import io.scos.connector.jira.internal.JiraPages.Page;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * Jira 목록 봉투 읽기(TS readPage·completeEmbeddedCollection·identity): 잘리거나 빠진 봉투를 마지막 페이지로 부르지 않고 실패한다.
 * 목록은 startAt·total·isLast, 이슈 검색은 nextPageToken·isLast로 다음 위치를 정한다.
 */
@DisplayName("Jira 목록 봉투는 TS readPage처럼 다음 위치를 정하고 잘림을 거부한다")
class JiraPagesTest {
  static JsonNode json(String text) {
    return JiraFixtures.JSON.readTree(text);
  }

  /** 데이터셋에서 이 경로·쿼리 값이 든 라우트의 첫 응답 본문. */
  static JsonNode datasetBody(String path, String param, String value) {
    for (JsonNode r : JiraFixtures.dataset().get("routes"))
      if (r.get("path").asString().equals(path)
          && value.equals(r.get("params").path(param).asString(null)))
        return r.get("responses").get(0).get("json");
    throw new IllegalStateException("데이터셋에 " + path + " " + param + "=" + value + "가 없다");
  }

  @Nested
  @DisplayName("목록 봉투를 읽으면")
  class Listing {
    @Test
    @DisplayName("total이 남았으면 받은 개수만큼 뒤가 다음 위치다")
    void continuesAfterReceivedEntries() {
      // given
      JsonNode first = datasetBody("/rest/api/3/project/search", "startAt", "0");

      // when
      Page page = JiraPages.listing(first, "values", 0);

      // then
      assertThat(page.continuation()).contains(new Continuation.Offset(1));
    }

    @Test
    @DisplayName("받은 개수가 total에 닿으면 isLast 없이도 마지막이다")
    void endsAtTotal() {
      // when
      Page page = JiraPages.listing(json("{\"values\":[{\"id\":3}],\"total\":3}"), "values", 2);

      // then
      assertThat(page.continuation()).isEmpty();
    }

    @Test
    @DisplayName("total에 못 미치면 isLast 없이도 받은 개수만큼 뒤로 이어간다")
    void continuesBelowTotal() {
      // when
      Page page =
          JiraPages.listing(json("{\"values\":[{\"id\":3},{\"id\":4}],\"total\":5}"), "values", 2);

      // then
      assertThat(page.continuation()).contains(new Continuation.Offset(4));
    }

    @Test
    @DisplayName("데이터셋 마지막 프로젝트 페이지는 마지막이다")
    void datasetSecondProjectPageIsLast() {
      // given
      JsonNode second = datasetBody("/rest/api/3/project/search", "startAt", "1");

      // when
      Page page = JiraPages.listing(second, "values", 1);

      // then
      assertThat(page.continuation()).isEmpty();
    }

    @Test
    @DisplayName("isLast가 true면 total 없이도 마지막이다")
    void endsWhenIsLast() {
      // when
      Page page = JiraPages.listing(json("{\"values\":[{\"id\":9}],\"isLast\":true}"), "values", 0);

      // then
      assertThat(page.continuation()).isEmpty();
    }

    @Test
    @DisplayName("봉투 없는 배열은 그 자체가 전부다")
    void bareArrayIsComplete() {
      // when
      Page page =
          JiraPages.listing(
              json("[{\"id\":\"summary\"},{\"id\":\"customfield_10010\"}]"), "values", 0);

      // then
      assertThat(page.entries()).hasSize(2);
      assertThat(page.continuation()).as("다음 페이지 없음").isEmpty();
    }

    @Test
    @DisplayName("isLast가 false면 total 없이 받은 개수만큼 뒤로 이어간다")
    void continuesWhenNotLast() {
      // when
      Page page =
          JiraPages.listing(
              json("{\"values\":[{\"id\":1},{\"id\":2}],\"isLast\":false}"), "values", 4);

      // then
      assertThat(page.continuation()).contains(new Continuation.Offset(6));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
          "남은 항목이 있는데 빈 페이지|{\"values\":[],\"total\":10}|Incomplete Jira pagination envelope",
          "마지막인지 판단할 수 없음|{\"values\":[{\"id\":1}]}|Incomplete Jira pagination envelope",
          "total이 숫자가 아님|{\"values\":[{\"id\":1}],\"total\":\"9\"}|Incomplete Jira pagination envelope",
          "요청과 다른 위치|{\"values\":[{\"id\":1}],\"startAt\":5,\"total\":9}|Jira returned wrong page offset",
          "목록 키가 배열이 아님|{\"values\":{}}|Invalid Jira collection response",
          "항목이 객체가 아님|{\"values\":[1],\"isLast\":true}|Invalid Jira object",
          "봉투가 객체가 아님|\"text\"|Invalid Jira object"
        })
    @DisplayName("봉투가 잘리거나 어긋나면 완료로 부르지 않고 실패한다")
    void rejectsIncompleteEnvelope(String label, String body, String message) {
      // when·then
      assertThatThrownBy(() -> JiraPages.listing(json(body), "values", 0))
          .isInstanceOf(JiraProtocolException.class)
          .hasMessage(message);
    }
  }

  @Nested
  @DisplayName("이슈 검색 페이지를 읽으면")
  class Search {
    @Test
    @DisplayName("isLast가 아니면 nextPageToken이 다음 위치다")
    void continuesWithNextPageToken() {
      // given
      JsonNode first =
          datasetBody("/rest/api/3/search/jql", "jql", "project = 10000 ORDER BY id ASC");

      // when
      Page page = JiraPages.search(first, Optional.empty());

      // then
      assertThat(page.continuation()).contains(new Continuation.Cursor("tok-page-2"));
    }

    @Test
    @DisplayName("isLast면 마지막이다")
    void endsWhenIsLast() {
      // given
      JsonNode second = datasetBody("/rest/api/3/search/jql", "nextPageToken", "tok-page-2");

      // when
      Page page = JiraPages.search(second, Optional.of("tok-page-2"));

      // then
      assertThat(page.continuation()).isEmpty();
    }

    @Test
    @DisplayName("봉투 없는 배열 응답은 그 자체가 전부다")
    void bareArrayIsComplete() {
      // when
      Page page = JiraPages.search(json("[{\"id\":\"40001\"}]"), Optional.empty());

      // then
      assertThat(page.entries()).hasSize(1);
      assertThat(page.continuation()).as("다음 페이지 없음").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
          "같은 토큰|{\"issues\":[],\"isLast\":false,\"nextPageToken\":\"same\"}",
          "토큰 없음|{\"issues\":[],\"isLast\":false}",
          "빈 토큰|{\"issues\":[],\"nextPageToken\":\"\"}"
        })
    @DisplayName("커서가 전진하지 않으면 실패한다")
    void rejectsStalledCursor(String label, String body) {
      // when·then
      assertThatThrownBy(() -> JiraPages.search(json(body), Optional.of("same")))
          .isInstanceOf(JiraProtocolException.class)
          .hasMessage("Jira search did not advance");
    }
  }

  @Nested
  @DisplayName("이슈에 내장된 자식 목록을 읽으면")
  class Embedded {
    @Test
    @DisplayName("total이 받은 개수와 같으면 그대로 쓴다")
    void usesCompleteList() {
      // given
      JsonNode comments = json("{\"total\":1,\"comments\":[{\"id\":\"1\",\"body\":\"Complete\"}]}");

      // when
      Optional<List<JsonNode>> complete = JiraPages.completeEmbedded(comments, "comments");

      // then
      assertThat(complete).hasValueSatisfying(list -> assertThat(list).hasSize(1));
    }

    @Test
    @DisplayName("startAt이 0이면 완전한 목록이다")
    void acceptsZeroStart() {
      // given
      JsonNode comments = json("{\"total\":1,\"startAt\":0,\"comments\":[{\"id\":\"1\"}]}");

      // when
      Optional<List<JsonNode>> complete = JiraPages.completeEmbedded(comments, "comments");

      // then
      assertThat(complete).isPresent();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
          "전체 개수가 더 많음|{\"total\":2,\"comments\":[{\"id\":\"partial\"}]}",
          "total이 숫자가 아님|{\"total\":\"1\",\"comments\":[{\"id\":\"1\"}]}",
          "시작 위치가 숫자가 아님|{\"total\":1,\"startAt\":\"0\",\"comments\":[{\"id\":\"1\"}]}",
          "시작 위치가 0이 아님|{\"total\":1,\"startAt\":1,\"comments\":[{\"id\":\"partial\"}]}",
          "전체 개수를 모름|{\"comments\":[]}",
          "목록이 아님|{\"total\":0,\"comments\":{}}",
          "객체가 아님|[]"
        })
    @DisplayName("완전하다고 볼 수 없으면 다시 읽게 한다")
    void asksToReadAgain(String label, String body) {
      // when
      Optional<List<JsonNode>> complete = JiraPages.completeEmbedded(json(body), "comments");

      // then
      assertThat(complete).isEmpty();
    }
  }

  @Nested
  @DisplayName("객체 식별자를 읽으면")
  class Identity {
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        value = {
          "{\"id\":\"10000\"}|10000",
          "{\"id\":9}|9",
          "{\"id\":1e21}|1e+21",
          "{\"id\":2.0}|2"
        })
    @DisplayName("식별자는 문자열이나 숫자 id다(수는 JS String(id) 표기)")
    void readsIdentity(String body, String id) {
      // when·then
      assertThat(JiraPages.identity(json(body))).isEqualTo(id);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {"{}", "{\"id\":\"\"}", "{\"id\":true}"})
    @DisplayName("식별자가 없거나 문자열·수가 아니면 실패한다")
    void rejectsMissingIdentity(String body) {
      // when·then
      assertThatThrownBy(() -> JiraPages.identity(json(body)))
          .isInstanceOf(JiraProtocolException.class)
          .hasMessage("Jira object has no identity");
    }
  }
}
