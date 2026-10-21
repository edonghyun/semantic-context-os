package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.CollectionRuntime;
import io.scos.connector.jira.internal.JiraRuntimeScenario.ReportView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 실제 수집 런타임 위의 Jira(TESTING §3.6 5층): run 보고·저장된 체크포인트·원본이 TS CollectionRuntime의 같은
 * 실행(expected-raw-items, expected-resume)과 같다. 페이지 중간 중단은 그 배치를 커밋하지 않고, 재개는 저장된 체크포인트에서 이어 한 번 수집한
 * 것과 같은 원본을 남긴다.
 */
@DisplayName("수집 런타임 위 Jira는 TS CollectionRuntime과 같이 끊기고 재개한다(§3.6 5층)")
class JiraCollectionRuntimeTest {
  static final JsonNode TS_FIRST = JiraFixtures.read("expected-raw-items.json");
  static final JsonNode TS_RESUME = JiraFixtures.read("expected-resume.json");

  final JiraRuntimeScenario jira = new JiraRuntimeScenario();

  @AfterEach
  void tearDown() {
    jira.close();
  }

  @Nested
  @DisplayName("처음 수집하면")
  class FirstCollection {
    @Test
    @DisplayName("run 보고가 TS와 같다")
    void reportMatchesTs() {
      // when
      CollectionRuntime.Report report = jira.collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(TS_FIRST.get("report")));
    }

    @Test
    @DisplayName("저장된 체크포인트가 TS와 같다")
    void storedCheckpointMatchesTs() {
      // when
      jira.collect();

      // then
      assertThat(jira.storedCheckpointOrNull()).isEqualTo(JiraTsRuns.firstCollectionCheckpoint());
    }

    @Test
    @DisplayName("active 원본과 버전이 TS와 같다")
    void activeItemsMatchTs() {
      // when
      jira.collect();

      // then
      assertThat(jira.activeVersions())
          .isEqualTo(JiraRuntimeScenario.versionsOf(TS_FIRST.get("items")));
    }
  }

  @Nested
  @DisplayName("40005 요청이 인증 실패(401)로 끊기면")
  class Crash {
    @BeforeEach
    void givenCrashAtIssue40005() {
      jira.givenCrashAtIssue40005();
    }

    @Test
    @DisplayName("run 보고가 TS 첫 run과 같다(그 배치는 커밋되지 않는다)")
    void reportMatchesTs() {
      // when
      CollectionRuntime.Report report = jira.collect();

      // then
      assertThat(ReportView.of(report))
          .isEqualTo(ReportView.expected(TS_RESUME.get("firstRun").get("report")));
    }

    @Test
    @DisplayName("저장된 체크포인트가 TS 첫 run의 것과 같다")
    void storedCheckpointMatchesTs() {
      // when
      jira.collect();

      // then
      assertThat(jira.storedCheckpointOrNull()).isEqualTo(JiraTsRuns.crashedRunCheckpoint());
    }
  }

  @Nested
  @DisplayName("40005에서 끊긴 run 뒤 다시 수집하면")
  class Resume {
    @BeforeEach
    void givenCrashedRun() {
      jira.givenCrashedRun();
    }

    @Test
    @DisplayName("run 보고가 TS 둘째 run과 같다")
    void reportMatchesTs() {
      // when
      CollectionRuntime.Report report = jira.collect();

      // then
      assertThat(ReportView.of(report))
          .isEqualTo(ReportView.expected(TS_RESUME.get("secondRun").get("report")));
    }

    @Test
    @DisplayName("active 원본이 한 번에 수집한 것과 같다(중복 없음)")
    void activeItemsMatchSingleCollection() {
      // when
      jira.collect();

      // then
      assertThat(jira.activeVersions())
          .isEqualTo(JiraRuntimeScenario.versionsOf(TS_RESUME.get("items")));
    }
  }
}
