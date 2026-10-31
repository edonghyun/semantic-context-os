package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("색인 열기 재시도는 대기 정책을 통해 멈추고 이어 간다(B8)")
class ClientRetryTest {
  private static final Duration RETRY = Duration.ofSeconds(1);

  @Test
  @DisplayName("열기가 한 번 실패하면 두 번째 열기의 결과를 돌려준다")
  void returnsSuccessfulRetry() {
    // given
    var scenario = new ClientRetryScenario();
    scenario.givenOpenFailures(1);
    // when
    Optional<String> opened = scenario.open();
    // then
    assertThat(opened).contains("opened");
    assertThat(scenario.attempts()).isEqualTo(2);
  }

  @Test
  @DisplayName("열기가 계속 실패하면 세 번 시도하고 빈 결과를 돌려준다")
  void givesUpAfterMaximumAttempts() {
    // given
    var scenario = new ClientRetryScenario();
    scenario.givenOpenFailures(3);
    // when
    Optional<String> opened = scenario.open();
    // then
    assertThat(opened).isEmpty();
    assertThat(scenario.attempts()).isEqualTo(3);
  }

  @Test
  @DisplayName("두 번 실패한 뒤 성공하면 재시도 사이에만 두 번 기다린다")
  void waitsOnlyBetweenAttempts() {
    // given
    var scenario = new ClientRetryScenario();
    scenario.givenOpenFailures(2);

    // when
    scenario.open();

    // then
    assertThat(scenario.waits()).containsExactly(RETRY, RETRY);
  }

  @Test
  @DisplayName("마지막 열기도 실패하면 더 기다리지 않는다")
  void doesNotWaitAfterExhaustion() {
    // given
    var scenario = new ClientRetryScenario();
    scenario.givenOpenFailures(3);

    // when
    scenario.open();

    // then
    assertThat(scenario.waits()).containsExactly(RETRY, RETRY);
  }

  @Test
  @DisplayName("대기가 취소되면 다음 열기를 시도하지 않는다")
  void stopsWhenWaitingIsCancelled() {
    // given
    var scenario = new ClientRetryScenario();
    scenario.givenOpenFailures(3);
    scenario.cancelDuringRetryWait();

    // when
    scenario.open();

    // then
    assertThat(scenario.attempts()).isEqualTo(1);
  }
}
