package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("접근 상태 CLI는 실행 중 상태와 건강 추세를 드러낸다(O-3)")
class AccessHealthCliTest {
  @Test
  @DisplayName("실행 중 접근 run을 skipped가 아닌 running으로 출력한다")
  void runningAccessIsShownAsRunning() {
    var scenario = QueryScenario.replay(QueryScenario.fixture().get("scenario"));
    scenario.givenRunningAccess();
    var output = scenario.run(List.of("source", "status", "slack-main"), true);
    // then
    assertThat(
            QueryScenario.JSON.readTree(output.getFirst()).path("access").path("result").asString())
        .isEqualTo("running");
  }
}
