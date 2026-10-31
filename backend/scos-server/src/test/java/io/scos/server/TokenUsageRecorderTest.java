package io.scos.server;

import static io.scos.server.TokenUsageScenario.EARLIER;
import static io.scos.server.TokenUsageScenario.FIRST;
import static io.scos.server.TokenUsageScenario.LATEST;
import static io.scos.server.TokenUsageScenario.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("토큰 사용 기록기는 최근 시각을 모아 지연 반영한다")
class TokenUsageRecorderTest {
  private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
  private final TokenUsageScenario scenario = new TokenUsageScenario();

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("같은 토큰의 여러 사용은 가장 늦은 시각 하나로 반영된다")
  void keepsLatestUse() {
    scenario.record(TOKEN, FIRST);
    scenario.record(TOKEN, LATEST);
    scenario.record(TOKEN, EARLIER);

    scenario.tick();

    // then
    assertThat(scenario.lastUsed(TOKEN)).contains(LATEST);
  }

  @Test
  @DisplayName("반영 중인 쓰기가 끝나지 않아도 사용 기록은 완료된다")
  void writingDoesNotBlockRecording() throws Exception {
    scenario.record(TOKEN, FIRST);
    scenario.holdFlush();

    var completed = scenario.recordDuringFlush(TOKEN, LATEST);

    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("반영에 실패한 사용 기록은 다음 주기에 다시 쓴다")
  void retriesFailedBatch() {
    scenario.record(TOKEN, FIRST);
    scenario.failFlush();

    scenario.tick();

    // then
    assertThat(scenario.lastUsed(TOKEN)).contains(FIRST);
  }

  @Test
  @DisplayName("반영 중 추가한 사용 기록은 다음 주기에 보존된다")
  void keepsUsesRecordedDuringWrite() throws Exception {
    scenario.record(TOKEN, FIRST);
    scenario.holdFlush();
    scenario.recordDuringFlush(TOKEN, LATEST);
    scenario.finishFlush();

    scenario.tick();

    // then
    assertThat(scenario.lastUsed(TOKEN)).contains(LATEST);
  }

  @Test
  @DisplayName("실패 배치를 되돌려도 반영 중 기록한 더 늦은 시각은 보존된다")
  void failedBatchKeepsNewerPendingUse() throws Exception {
    scenario.record(TOKEN, FIRST);
    scenario.holdFlush();
    scenario.recordDuringFailedFlush(TOKEN, LATEST);

    scenario.tick();

    // then
    assertThat(scenario.lastUsed(TOKEN)).contains(LATEST);
  }

  @Test
  @DisplayName("반영 중 더 이른 시각을 기록해도 실패 배치의 최댓값은 보존된다")
  void failedBatchKeepsLaterSnapshot() throws Exception {
    scenario.record(TOKEN, LATEST);
    scenario.holdFlush();
    scenario.recordDuringFailedFlush(TOKEN, EARLIER);

    scenario.tick();

    // then
    assertThat(scenario.lastUsed(TOKEN)).contains(LATEST);
  }

  @Test
  @DisplayName("사용 시각은 주기가 오기 전에는 저장소에 쓰지 않는다")
  void waitsForTick() {
    scenario.record(TOKEN, FIRST);

    // then
    assertThat(scenario.writes()).isEmpty();
  }

  @Test
  @DisplayName("분마다 각 토큰의 최근 시각을 한 배치로 쓴다")
  void writesOneBatchPerMinute() {
    scenario.record(TOKEN, FIRST);
    scenario.record(TOKEN, LATEST);
    scenario.record(TokenUsageScenario.OTHER, EARLIER);

    scenario.tick();

    // then
    assertThat(scenario.periods()).containsExactly(ONE_MINUTE);
    assertThat(scenario.writes())
        .containsExactly(Map.of(TOKEN, LATEST, TokenUsageScenario.OTHER, EARLIER));
  }

  @Test
  @DisplayName("빈 다음 주기는 이전 사용 기록을 다시 쓰지 않는다")
  void drainsWrittenBatch() {
    scenario.record(TOKEN, FIRST);
    scenario.tick();

    scenario.tick();

    // then
    assertThat(scenario.writes()).containsExactly(Map.of(TOKEN, FIRST));
  }

  @Test
  @DisplayName("다음 주기에 새 사용 기록이 있으면 새 배치로 쓴다")
  void nextTickWritesNewUses() {
    scenario.record(TOKEN, FIRST);
    scenario.tick();
    scenario.record(TOKEN, LATEST);

    scenario.tick();

    // then
    assertThat(scenario.writes()).containsExactly(Map.of(TOKEN, FIRST), Map.of(TOKEN, LATEST));
  }

  @Test
  @DisplayName("닫을 때 다음 주기 전의 남은 사용 기록을 쓴다")
  void closeFlushesRemainingUses() {
    scenario.record(TOKEN, FIRST);

    scenario.close();

    // then
    assertThat(scenario.lastUsed(TOKEN)).contains(FIRST);
  }

  @Test
  @DisplayName("닫은 기록기는 다음 주기 작업을 취소한다")
  void closeCancelsScheduledTask() {
    scenario.close();

    // then
    assertThat(scenario.scheduledTasks()).isZero();
  }
}
