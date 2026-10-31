package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.slack.internal.SlackScenario.Collection;
import io.scos.spi.connector.CallExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 커넥터는 입력의 호출 실행기를 계약({@link CallExecutor})으로만 안다 — 재시도·대기·지표가 없는 실행기를 넘겨도 수집이 돈다. 제품 실행기 없이 어댑터를
 * 쓰는 바깥 사용자의 자리다(단계 2 API 설계 §1.2).
 */
@DisplayName("Slack 수집은 시도 한 번짜리 호출 실행기로도 돈다(실행기는 계약)")
class SlackSingleAttemptExecutorTest {
  final SlackScenario slack = new SlackScenario();

  @AfterEach
  void tearDown() {
    slack.close();
  }

  @Test
  @DisplayName("공개 채널을 수집하면 원본 변경이 담긴 페이지가 온다")
  void collectsWithPlainExecutor() {
    // when
    Collection collection = slack.collectWithSingleAttemptExecutor();

    // then
    assertThat(collection.mutations()).isPositive();
  }
}
