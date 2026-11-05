package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessFailureReason;
import io.scos.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@DisplayName("문서 차단 로그는 실제 결정 전이와 커밋 알림을 따른다")
class DocumentAccessLogTest {
  private final DocumentAccessScenario scenario = new DocumentAccessScenario();

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("첫 FAILED 결정도 원인 로그를 남긴다")
  void initialFailureIsReported() {
    try (var logs = LogCapture.of(DocumentIngestor.class)) {
      scenario.decideFailure(AccessFailureReason.MISSING_TARGET);
      // then
      assertThat(logs.events("projection.access.closed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("detail").path("reason").asString())
                      .isEqualTo("missing_target"));
    }
  }

  @Test
  @DisplayName("허용 결정은 차단 로그로 보고하지 않는다")
  void allowedDecisionIsNotReportedAsClosure() {
    try (var logs = LogCapture.of(DocumentIngestor.class)) {
      scenario.decideAllowed();
      // then
      assertThat(logs.events("projection.access.closed")).isEmpty();
    }
  }

  @Test
  @DisplayName("같은 FAILED 원인을 다시 결정해도 로그를 반복하지 않는다")
  void unchangedFailureDoesNotRepeatLog() {
    scenario.decideFailure(AccessFailureReason.MISSING_TARGET);
    try (var logs = LogCapture.of(DocumentIngestor.class)) {
      scenario.decideFailure(AccessFailureReason.MISSING_TARGET);
      // then
      assertThat(logs.events("projection.access.closed")).isEmpty();
    }
  }

  @Test
  @DisplayName("FAILED 원인이 달라지면 새 원인 로그를 남긴다")
  void changedFailureIsReported() {
    scenario.decideFailure(AccessFailureReason.MISSING_TARGET);
    try (var logs = LogCapture.of(DocumentIngestor.class)) {
      scenario.decideFailure(AccessFailureReason.EXPIRED);
      // then
      assertThat(logs.events("projection.access.closed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("detail").path("reason").asString()).isEqualTo("expired"));
    }
  }

  @Test
  @DisplayName("차단 로그는 Spring 커밋 알림 전에는 없고 커밋 뒤에 발행된다")
  void closureIsPublishedAfterCommitNotification() {
    scenario.beginCommitNotifications();
    try (var logs = LogCapture.of(DocumentIngestor.class)) {
      scenario.decideFailure(AccessFailureReason.EXPIRED);
      assertThat(logs.events("projection.access.closed")).isEmpty();
      scenario.commitNotifications();
      // then
      assertThat(logs.events("projection.access.closed")).hasSize(1);
    }
  }
}
