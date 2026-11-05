package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.AccessExpiry;
import io.scos.support.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Tag("db")
@Isolated
@DisplayName("수집과 만료는 소스부터 잠그고 최신 디렉터리 관측을 보존한다")
class AccessExpiryDirectoryRaceTest {
  @Test
  @DisplayName("소스를 점유한 수집과 디렉터리 만료가 겹쳐도 교착하지 않는다")
  void collectionAndExpiryDoNotDeadlock() throws Exception {
    try (var scenario = new AccessExpiryDirectoryRaceScenario();
        var logs = LogCapture.of(AccessExpiry.class)) {
      // when
      scenario.collectDuringExpiry();
      // then
      assertThat(logs.events("collection.access.expiry_failed")).isEmpty();
    }
  }

  @Test
  @DisplayName("소스 잠금 대기 중 수집한 신선한 그룹은 만료하지 않는다")
  void freshDirectoryWhileWaitingForSourceIsPreserved() throws Exception {
    try (var scenario = new AccessExpiryDirectoryRaceScenario()) {
      // when
      scenario.collectDuringExpiry();
      // then
      assertThat(scenario.members()).containsExactly("U2");
    }
  }
}
