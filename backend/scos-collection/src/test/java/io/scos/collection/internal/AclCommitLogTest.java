package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.connector.RawAcl;
import io.scos.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@DisplayName("원본 차단 로그는 만료 원인과 커밋 시점을 보존한다")
class AclCommitLogTest {
  private final AclCommitScenario scenario = AclCommitScenario.memory();

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("실행 중 상한 만료도 expired 원인을 기록한다")
  void runExpirationLogsCause() {
    try (var logs = LogCapture.of(AclCommit.class)) {
      scenario.expireBefore(AclCommitScenario.T0);
      // then
      assertThat(logs.events("collection.acl.closed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("detail").path("reason").asString()).isEqualTo("expired"));
    }
  }

  @Test
  @DisplayName("원본 차단 로그는 커밋 알림 뒤에만 발행된다")
  void rawClosureIsPublishedAfterCommitNotification() {
    scenario.beginCommitNotifications();
    try (var logs = LogCapture.of(AclCommit.class)) {
      scenario.access(RawAcl.failed(AccessFailureReason.MISSING_TARGET, AclCommitScenario.T1));
      assertThat(logs.events("collection.acl.closed")).isEmpty();
      scenario.commitNotifications();
      // then
      assertThat(logs.events("collection.acl.closed")).hasSize(1);
    }
  }
}
