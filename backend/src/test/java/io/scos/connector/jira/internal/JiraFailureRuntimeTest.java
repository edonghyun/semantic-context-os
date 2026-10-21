package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.CollectionRuntime;
import io.scos.connector.jira.internal.JiraRuntimeScenario.ReportView;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * 계속되는 장애 아래의 Jira 첫 수집(TESTING §3.6 5층, 실제 수집 런타임 + 메모리 포트): 재시도 소진(503·500·429)과 비재시도(401·필수 목록
 * 400)에서 run 보고·실패 종류·재시도 횟수·저장된 체크포인트·active 원본이 TS CollectionRuntime의 같은 실행과 같다. 기대값은 TS 생성기의
 * expected-failures.json이다(TS 재시도 대기는 0, Java는 대기 없는 실행기 — 둘 다 시도 상한 5).
 */
@DisplayName("계속되는 장애 아래 Jira 첫 수집은 TS와 같이 끝난다(§3.6 5층)")
class JiraFailureRuntimeTest {
  static final JsonNode TS = JiraFixtures.read("expected-failures.json");

  final JiraRuntimeScenario jira = new JiraRuntimeScenario();

  @AfterEach
  void tearDown() {
    jira.close();
  }

  static Stream<Arguments> scenarios() {
    return StreamSupport.stream(TS.get("scenarios").spliterator(), false)
        .map(s -> Arguments.of(s.get("failure").get("name").asString(), s));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  @DisplayName("run 보고가 TS와 같다")
  void reportMatchesTs(String name, JsonNode scenario) {
    // given
    jira.givenFailure(scenario.get("failure"));

    // when
    CollectionRuntime.Report report = jira.collect();

    // then
    assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(scenario.get("report")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  @DisplayName("run의 실패 종류가 TS와 같다")
  void errorKindMatchesTs(String name, JsonNode scenario) {
    // given
    jira.givenFailure(scenario.get("failure"));

    // when
    CollectionRuntime.Report report = jira.collect();

    // then
    assertThat(report.errorKind().orElse(null))
        .isEqualTo(scenario.get("run").get("errorKind").asString(null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  @DisplayName("장애 경로로 보낸 요청 수(재시도 포함)가 TS와 같다")
  void retriesLikeTs(String name, JsonNode scenario) {
    // given
    jira.givenFailure(scenario.get("failure"));

    // when
    jira.collect();

    // then
    assertThat(jira.requestsTo(scenario.get("failure").get("pathPrefix").asString()))
        .isEqualTo(scenario.get("failedRequests").asLong());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  @DisplayName("저장된 체크포인트가 TS와 같다(커밋한 페이지까지)")
  void checkpointMatchesTs(String name, JsonNode scenario) {
    // given
    jira.givenFailure(scenario.get("failure"));

    // when
    jira.collect();

    // then
    assertThat(jira.storedCheckpointOrNull())
        .isEqualTo(scenario.get("checkpoint").get("checkpoint"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  @DisplayName("active 원본이 TS와 같다")
  void activeItemsMatchTs(String name, JsonNode scenario) {
    // given
    jira.givenFailure(scenario.get("failure"));

    // when
    jira.collect();

    // then
    assertThat(jira.activeVersions().keySet())
        .containsExactlyInAnyOrderElementsOf(JiraTsRuns.strings(scenario.get("activeExternalIds")));
  }
}
