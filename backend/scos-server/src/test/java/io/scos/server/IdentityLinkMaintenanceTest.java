package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.support.LogCapture;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.ObjectMapper;

@Isolated
@DisplayName("일일 신원 연결 정비는 첫 tick와 24시간 경계를 따른다")
class IdentityLinkMaintenanceTest {
  private static final Duration BEFORE_DAILY = Duration.ofSeconds(86399);
  private static final Duration DAILY = Duration.ofHours(24);
  private final IdentityLinkScheduleScenario scenario = new IdentityLinkScheduleScenario();

  @Test
  @DisplayName("첫 tick는 모든 소스의 적격 신원을 연결한다")
  void firstTickReconcilesAllSources() {
    scenario.eligibleIdentity("slack-a", "U1");
    scenario.eligibleIdentity("slack-b", "U2");
    scenario.tick();
    // then
    assertThat(scenario.links())
        .extracting(link -> link.source().value())
        .containsExactly("slack-a", "slack-b");
  }

  @Test
  @DisplayName("23시간 59분 59초에는 추가 신원을 연결하지 않는다")
  void beforeTwentyFourHoursDoesNotReconcileAgain() {
    scenario.tick();
    scenario.eligibleIdentity("slack-a", "U1");
    scenario.advance(BEFORE_DAILY);
    scenario.tick();
    // then
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("정확히 24시간 뒤 새 적격 신원을 연결한다")
  void atTwentyFourHoursReconcilesAgain() {
    scenario.tick();
    scenario.eligibleIdentity("slack-a", "U1");
    scenario.advance(DAILY);
    scenario.tick();
    // then
    assertThat(scenario.links()).extracting(IdentityLink::sourceUserId).containsExactly("U1");
  }

  @Test
  @DisplayName("재시작 첫 tick는 전 프로세스 뒤 추가된 신원을 회복한다")
  void restartReconcilesOnFirstTick() {
    scenario.tick();
    scenario.eligibleIdentity("slack-a", "U1");
    scenario.restart();
    scenario.tick();
    // then
    assertThat(scenario.links()).extracting(IdentityLink::userId).containsExactly("alice");
  }

  @Test
  @DisplayName("취소된 tick는 연결을 시작하지 않는다")
  void cancellationDoesNotStartDailyReconciliation() {
    scenario.eligibleIdentity("slack-a", "U1");
    scenario.cancel();
    scenario.tick();
    // then
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("저장소 실패 뒤에도 due를 전진해 매 tick 재시도를 막는다")
  void failureAdvancesDueTime() {
    scenario.eligibleIdentity("slack-a", "U1");
    scenario.failStorage();
    scenario.tick();
    scenario.recoverStorage();
    scenario.advance(BEFORE_DAILY);
    scenario.tick();
    // then
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("실패한 작업도 24시간 뒤 다시 실행한다")
  void failedReconciliationRetriesNextDay() {
    scenario.eligibleIdentity("slack-a", "U1");
    scenario.failStorage();
    scenario.tick();
    scenario.recoverStorage();
    scenario.advance(DAILY);
    scenario.tick();
    // then
    assertThat(scenario.links()).extracting(IdentityLink::userId).containsExactly("alice");
  }

  @Test
  @DisplayName("일일 성공 로그는 신원 원문 없이 집계만 기록한다")
  void successLogsOnlyCounts() {
    scenario.eligibleIdentity("slack-a", "U1");
    try (var log = LogCapture.of(IdentityLinkMaintenance.class)) {
      scenario.tick();
      // then
      assertThat(log.events("admin.identity_reconcile.completed"))
          .containsExactly(
              new ObjectMapper()
                  .readTree(
                      "{\"level\":\"info\",\"event\":\"admin.identity_reconcile.completed\","
                          + "\"detail\":{\"linked\":1,\"unlinked\":0,\"unchanged\":0,\"failedSources\":0}}"));
    }
  }

  @Test
  @DisplayName("일일 실패는 예외 원문 없는 고정 이벤트로 남긴다")
  void failureLogsFixedEventWithoutPrivateMessage() {
    scenario.failStorage();
    try (var log = LogCapture.of(IdentityLinkMaintenance.class)) {
      scenario.tick();
      // then
      assertThat(log.events("admin.identity_reconcile.failed"))
          .containsExactly(
              new ObjectMapper()
                  .readTree(
                      "{\"level\":\"warn\",\"event\":\"admin.identity_reconcile.failed\","
                          + "\"detail\":{\"linked\":0,\"unlinked\":0,\"unchanged\":0,\"failedSources\":1}}"));
    }
  }

  @Test
  @DisplayName("데몬 tick는 일일 연결 정비를 실행한다")
  void daemonTickRunsIdentityMaintenance() {
    scenario.eligibleIdentity("slack-a", "U1");
    try (var daemon = new DaemonScenario().identityMaintenance(scenario.maintenance())) {
      daemon.tickWithoutLoop();
      // then
      assertThat(scenario.links()).extracting(IdentityLink::userId).containsExactly("alice");
    }
  }
}
