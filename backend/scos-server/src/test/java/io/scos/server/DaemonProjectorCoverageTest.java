package io.scos.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.projection.Projectors;
import io.scos.connector.slack.SlackProjector;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 데몬 조립 검사(ADR-0022 §5, F4 리뷰 A-M5): 수집 어댑터가 있는 kind는 투영기도 있어야 한다 — 없으면 투영 요청이
 * SKIPPED_UNKNOWN_KIND로 소모돼 나중에 투영기를 넣어도 다음 변경 전까지 투영되지 않는다.
 */
@DisplayName("데몬은 수집 어댑터 kind마다 투영기가 있어야 기동한다(ADR-0022 §5)")
class DaemonProjectorCoverageTest {
  static final Projectors SLACK_ONLY =
      Projectors.of(List.of(new SlackProjector(new ObjectMapper())));

  @Test
  @DisplayName("모든 어댑터 kind에 투영기가 있으면 통과한다")
  void passesWhenEveryKindIsProjected() {
    // when·then
    assertThatCode(() -> DaemonConfiguration.requireProjectors(Set.of("slack"), SLACK_ONLY))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("투영기가 없는 어댑터 kind를 밝혀 기동을 거부한다")
  void rejectsKindWithoutProjector() {
    // when·then
    assertThatThrownBy(
            () -> DaemonConfiguration.requireProjectors(Set.of("jira", "slack"), SLACK_ONLY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("source adapter kinds without a projector: [jira]");
  }
}
