package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.notion.NotionAssembly;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 재조정 요청에 사용하는 합성 데이터셋의 응답 계약. */
@DisplayName("Notion 합성 데이터셋은 재조정 조회 계약을 지킨다")
class NotionDatasetTest {
  final FakeNotionServer server = NotionAssembly.server();
  final NotionApiClient client =
      NotionAssembly.client(server.baseUrl(), NotionClientScenario.TIMEOUT);

  @AfterEach
  void close() {
    server.close();
  }

  @Test
  @DisplayName("전체 행 조회 fixture는 생성 시각 순서로 페이지를 나눈다")
  void fullRowsAreOrderedAcrossPages() {
    // when
    var first =
        client.queryWindow(
            server.token(),
            "ds-tasks",
            Optional.empty(),
            NotionRowQuery.all(),
            NotionClientScenario.TIMEOUT);
    var next =
        client.queryWindow(
            server.token(),
            "ds-tasks",
            first.page().nextCursor(),
            NotionRowQuery.all(),
            NotionClientScenario.TIMEOUT);
    // then
    assertThat(first.page().results())
        .extracting(row -> row.path("id").asString())
        .containsExactly("ds-sub", "row-alert");
    assertThat(next.page().results())
        .extracting(row -> row.path("id").asString())
        .containsExactly("row-runbook", "row-drill");
  }
}
