package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("source status는 수집을 켠 경우에만 접근 상태를 덧붙인다")
class QueryAccessStatusTest {
  private QueryScenario scenario;

  @BeforeAll
  void setUp() {
    scenario = QueryScenario.replay(QueryScenario.fixture().get("scenario"));
  }

  @Test
  @DisplayName("기본 CLI 출력은 접근 필드가 없다")
  void disabledOutputKeepsExistingShape() {
    var output = scenario.run(List.of("source", "status", "slack-main"));
    // then
    assertThat(QueryScenario.JSON.readTree(output.getFirst()).has("access")).isFalse();
  }

  @Test
  @DisplayName("수집을 켠 CLI는 독립 접근 결과를 내보낸다")
  void enabledOutputIncludesAccessStatus() {
    var output = scenario.run(List.of("source", "status", "slack-main"), true);
    // then
    assertThat(
            QueryScenario.JSON.readTree(output.getFirst()).path("access").path("result").asString())
        .isEqualTo("skipped");
  }

  @Test
  @DisplayName("없는 소스는 수집이 켜져도 null이다")
  void absentSourceRemainsNull() {
    var output = scenario.run(List.of("source", "status", "absent"), true);
    // then
    assertThat(output).containsExactly("null");
  }
}
