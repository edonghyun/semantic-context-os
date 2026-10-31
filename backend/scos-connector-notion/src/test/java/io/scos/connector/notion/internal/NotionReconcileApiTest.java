package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.notion.internal.NotionClientScenario.Operation;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("재조정 API는 불완전한 탐색을 성공으로 처리하지 않는다")
class NotionReconcileApiTest {
  static final Instant LOWER_BOUND = Instant.parse("2026-10-25T00:00:00Z");
  final NotionClientScenario notion = new NotionClientScenario();

  @AfterEach
  void close() {
    notion.close();
  }

  @Test
  @DisplayName("incomplete에 이유가 없어도 검색을 실패시킨다")
  void rejectsIncompleteWithoutReason() {
    // given
    var response = NotionFixtures.JSON.readTree(NotionApiClientTest.EMPTY_SEARCH);
    response.withObject("/request_status").put("type", "incomplete");
    notion.answersOk(Operation.SEARCH, response);
    // when
    var outcome = notion.outcome(Operation.SEARCH);
    // then
    assertThat(outcome.failure().message())
        .isEqualTo("Notion API error: incomplete_search_results");
  }

  @Test
  @DisplayName("기존 data source 조회에 편집 시각 하한을 보낸다")
  void filtersRowsByEditedTime() {
    // given
    notion.changedRowsAreEmpty();
    // when
    notion.queryChangedRows(LOWER_BOUND);
    // then
    var body = notion.requestBody();
    assertThat(body.at("/filter/last_edited_time/on_or_after").asString())
        .isEqualTo("2026-10-25T00:00:00Z");
  }

  @Test
  @DisplayName("변경 행은 생성 시각 오름차순으로 요청한다")
  void sortsRowsByCreatedTime() {
    // given
    notion.changedRowsAreEmpty();
    // when
    notion.queryChangedRows(LOWER_BOUND);
    // then
    var body = notion.requestBody();
    assertThat(body.path("sorts").toString())
        .isEqualTo("[{\"timestamp\":\"created_time\",\"direction\":\"ascending\"}]");
  }

  @Test
  @DisplayName("단건 블록에서 현재 부모를 읽는다")
  void retrievesBlockParent() {
    // given
    notion.parentBlockIsUnderRoot();
    // when
    var result = notion.retrieveParentBlock();
    // then
    assertThat(result.at("/parent/page_id").asString()).isEqualTo("root-wiki");
  }

  @Test
  @DisplayName("알려진 query cap은 분할 가능한 제한 결과로 돌려준다")
  void returnsTypedQueryLimit() {
    // given
    notion.queryIsCapped();
    // when
    var result = notion.queryWindow();
    // then
    assertThat(result).isInstanceOf(NotionApiClient.CappedRows.class);
  }
}
