package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Slack 어댑터는 생성 응답과 실측 모양 표본을 같은 계약으로 읽는다")
class SlackRecordedResponseTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("탈퇴자 필드 누락·게스트·봇·DM이 있어도 디렉터리 순회를 마친다")
  void responseShapeCompletes(boolean recorded) {
    try (var slack = new SlackAccessScenario()) {
      slack.givenResponseShape(recorded);
      // when·then
      assertThat(slack.collect().failure()).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("탈퇴자·게스트·봇은 정회원 그룹에 포함하지 않는다")
  void responseShapeRestrictsRegularMembers(boolean recorded) {
    try (var slack = new SlackAccessScenario()) {
      slack.givenResponseShape(recorded);
      // when·then
      assertThat(slack.collect().group("~members").members()).containsExactly("U1");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("DM에 정회원 그룹 참조를 추가하지 않는다")
  void responseShapeRestrictsDirectMessage(boolean recorded) {
    try (var slack = new SlackAccessScenario()) {
      slack.givenResponseShape(recorded);
      // when·then
      assertThat(slack.collect().group("C1").members()).containsExactly("U2");
    }
  }
}
