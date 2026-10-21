package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** readiness = 첫 사이클 완료(ADR-0020 §3). */
@DisplayName("readiness는 첫 사이클 완료다(ADR-0020 §3)")
class DaemonReadinessIndicatorTest {
  AtomicBoolean done = new AtomicBoolean(false);
  DaemonReadinessIndicator indicator = new DaemonReadinessIndicator(done::get);

  @Test
  @DisplayName("첫 사이클 전 readiness는 OUT_OF_SERVICE(firstCycleDone=false)다")
  void outOfServiceUntilFirstCycle() {
    // when
    Health health = indicator.health();

    // then
    assertThat(health)
        .extracting(Health::getStatus, h -> h.getDetails().get("firstCycleDone"))
        .containsExactly(Status.OUT_OF_SERVICE, false);
  }

  @Test
  @DisplayName("첫 사이클 뒤 readiness는 UP(firstCycleDone=true)이다")
  void upAfterFirstCycle() {
    // given
    done.set(true);

    // when
    Health health = indicator.health();

    // then
    assertThat(health)
        .extracting(Health::getStatus, h -> h.getDetails().get("firstCycleDone"))
        .containsExactly(Status.UP, true);
  }
}
