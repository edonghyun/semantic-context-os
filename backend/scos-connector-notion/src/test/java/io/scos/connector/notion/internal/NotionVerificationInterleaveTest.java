package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("부재 복구는 안전한 응답 경계에서 내부 증분과 교대한다")
class NotionVerificationInterleaveTest {
  final NotionVerificationInterleaveScenario context = new NotionVerificationInterleaveScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  @DisplayName("긴 부재 본문 복구 중 due가 되면 다음 복구 응답보다 증분을 먼저 완료한다")
  void incrementalRunsBeforeRecoveryContinues() {
    // when
    context.collect();
    // then
    assertThat(context.incrementalRanBeforeRecoveryContinued()).isTrue();
  }
}
