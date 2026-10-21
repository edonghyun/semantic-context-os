package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.TsRawViews.CheckpointView;
import io.scos.connector.notion.internal.NotionScenario.Collection;
import io.scos.connector.notion.internal.NotionScenario.Config;
import io.scos.connector.notion.internal.NotionTsRuns.TsRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 검색 모드(체크포인트 v1·v2)와 roots + discoverSharedPages(TESTING §3.6 3층, expected-modes.json): 같은 가짜
 * Notion에서 Java가 낸 페이지(변경·거부·체크포인트)·요청·원본이 TS와 같다. 검색 모드는 active 단계 뒤 휴지통 단계를 걷고, 저장된 v1·v2 체크포인트에서
 * 이어 간다.
 */
@DisplayName("Notion 검색·discover 모드는 TS와 같은 페이지·요청을 낸다(§3.6 3층)")
class NotionModesTest {
  static final JsonNode MODES = NotionFixtures.read("expected-modes.json");

  final NotionScenario notion = new NotionScenario();

  @AfterEach
  void tearDown() {
    notion.close();
  }

  static CheckpointView before(JsonNode run) {
    return CheckpointView.expected(run.get("checkpointBefore"));
  }

  @Nested
  @DisplayName("검색 모드로 처음부터 수집하면 TS와 같다")
  class Search {
    final TsRun ts = TsRun.of(MODES.get("search"));

    @Test
    @DisplayName("페이지마다 같은 변경·거부·v2 체크포인트를 낸다")
    void pagesMatchTs() {
      // when
      Collection java = notion.collect(Config.SEARCH);

      // then
      assertThat(java.pageViews()).containsExactlyElementsOf(ts.pages());
    }

    @Test
    @DisplayName("같은 요청을 같은 순서로 보낸다")
    void sendsSameRequests() {
      // when
      notion.collect(Config.SEARCH);

      // then
      assertThat(notion.requests()).containsExactlyElementsOf(ts.requests());
    }
  }

  @Nested
  @DisplayName("v1 체크포인트(검색 커서)에서 재개하면 TS와 같다")
  class ResumeV1 {
    final JsonNode run = MODES.get("searchResumeV1");
    final TsRun ts = TsRun.of(run);

    @Test
    @DisplayName("같은 페이지를 낸다")
    void pagesMatchTs() {
      // when
      Collection java = notion.collectFrom(Config.SEARCH, before(run).checkpoint());

      // then
      assertThat(java.pageViews()).containsExactlyElementsOf(ts.pages());
    }

    @Test
    @DisplayName("저장된 커서부터 요청한다")
    void sendsSameRequests() {
      // when
      notion.collectFrom(Config.SEARCH, before(run).checkpoint());

      // then
      assertThat(notion.requests()).containsExactlyElementsOf(ts.requests());
    }
  }

  @Nested
  @DisplayName("v2 체크포인트(블록 대기열)에서 재개하면 TS와 같다")
  class ResumeV2 {
    final JsonNode run = MODES.get("searchResumeV2");
    final TsRun ts = TsRun.of(run);

    @Test
    @DisplayName("같은 페이지를 낸다")
    void pagesMatchTs() {
      // when
      Collection java = notion.collectFrom(Config.SEARCH, before(run).checkpoint());

      // then
      assertThat(java.pageViews()).containsExactlyElementsOf(ts.pages());
    }

    @Test
    @DisplayName("블록 대기열을 검색보다 먼저 요청한다")
    void sendsSameRequests() {
      // when
      notion.collectFrom(Config.SEARCH, before(run).checkpoint());

      // then
      assertThat(notion.requests()).containsExactlyElementsOf(ts.requests());
    }
  }

  @Nested
  @DisplayName("discoverSharedPages를 켜고 수집하면 TS와 같다")
  class Discover {
    final TsRun ts = TsRun.of(MODES.get("discover"));

    @Test
    @DisplayName("트리 순회 뒤 검색 단계까지 같은 페이지를 낸다")
    void pagesMatchTs() {
      // when
      Collection java = notion.collect(Config.DISCOVER);

      // then
      assertThat(java.pageViews()).containsExactlyElementsOf(ts.pages());
    }

    @Test
    @DisplayName("같은 요청을 같은 순서로 보낸다")
    void sendsSameRequests() {
      // when
      notion.collect(Config.DISCOVER);

      // then
      assertThat(notion.requests()).containsExactlyElementsOf(ts.requests());
    }
  }
}
