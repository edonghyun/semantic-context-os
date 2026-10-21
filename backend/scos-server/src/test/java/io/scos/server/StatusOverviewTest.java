package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * `status`(status.sh 대체: launchd를 뺀 소스 run·점유·엔진 최신성·serve 로그 경고). TS에 같은 명령이 없어 바이트 비교 대신 각 부분의
 * 의미를 본다. 상태는 조회 CLI의 TS 각본({@link QueryScenario}) — run 셋(jira 둘, slack 하나), tiro-main의 남의 점유.
 */
@Tag("db")
@DisplayName("status는 소스 상태·run·점유·최신성·로그 경고를 한 문서로 낸다")
class StatusOverviewTest {
  static final String WARN_LINE = "{\"level\":\"warn\",\"event\":\"supervisor.source.error\"}";

  static List<String> texts(JsonNode array, String field) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.get(field).asString()));
    return out;
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("TS 조회 각본 위에서 status --log를 실행하면")
  class OnQueryScenario {
    List<String> outputs;
    JsonNode overview;

    @BeforeAll
    void runStatus(@TempDir Path dir) throws IOException {
      QueryScenario scenario = QueryScenario.replay(QueryScenario.fixture().get("scenario"));
      Path log =
          Files.writeString(dir.resolve("serve.log"), "{\"level\":\"info\"}\n" + WARN_LINE + "\n");
      outputs = scenario.run(List.of("status", "--log", log.toString()));
      overview = QueryScenario.JSON.readTree(outputs.getFirst());
    }

    @Test
    @DisplayName("결과는 JSON 문서 하나다")
    void singleDocument() {
      // then
      assertThat(outputs).hasSize(1);
    }

    @Test
    @DisplayName("소스를 id 순으로 싣는다")
    void sourcesInIdOrder() {
      // then
      assertThat(texts(overview.get("sources"), "sourceInstanceId"))
          .containsExactly(
              "drive-main",
              "jira-main",
              "notion-discover",
              "notion-roots",
              "notion-search",
              "slack-main",
              "tiro-main");
    }

    @Test
    @DisplayName("최근 run은 시작 시각·run id 역순이다")
    void recentRunsNewestFirst() {
      // then
      assertThat(texts(overview.get("recentRuns"), "runId"))
          .containsExactly("run-3", "run-2", "run-1");
    }

    @Test
    @DisplayName("점유는 만료된 것까지 모두 싣고 살아 있는 것만 active다")
    void leasesWithActiveFlag() {
      // given: 점유별 active 표시
      Map<String, Boolean> active = new TreeMap<>();
      overview
          .get("leases")
          .forEach(
              l -> active.put(l.get("sourceInstanceId").asString(), l.get("active").asBoolean()));

      // then
      assertThat(active)
          .containsExactlyEntriesOf(
              new TreeMap<>(Map.of("jira-main", false, "slack-main", false, "tiro-main", true)));
    }

    @Test
    @DisplayName("유닛이 없는 공간의 최신성은 확인 불가(null)다")
    void freshnessUnavailable() {
      // then
      assertThat(QueryScenario.JSON.writeValueAsString(overview.get("freshness")))
          .isEqualTo(
              "[{\"space\":\"legacy\",\"latestCapturedAt\":null,\"staleHours\":null,\"stale\":false}]");
    }

    @Test
    @DisplayName("로그의 warn·error 줄을 싣는다")
    void logProblems() {
      // given: 문서에 실린 문제 줄
      List<String> problems = new ArrayList<>();
      overview.get("log").get("problems").forEach(line -> problems.add(line.asString()));

      // then
      assertThat(problems).containsExactly(WARN_LINE);
    }
  }
}
