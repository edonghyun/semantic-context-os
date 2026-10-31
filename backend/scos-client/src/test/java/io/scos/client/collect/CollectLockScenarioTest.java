package io.scos.client.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("process")
@DisplayName("잠금 준비 실패는 자식 JVM을 회수하고 원인을 보존한다(CG-6)")
class CollectLockScenarioTest {
  @Test
  @DisplayName("잘못된 준비 신호를 거부하면 잠금 보유 자식을 회수한다")
  void reapsHolderWhenReadyIsInvalid(@TempDir Path root) {
    // given
    try (var scenario = new CollectLockPreparationScenario(root)) {
      // when
      Throwable failure = scenario.rejectInvalidReady();
      // then
      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("given 준비 실패: READY 신호");
      assertThat(scenario.holderAlive()).isFalse();
    }
  }

  @ParameterizedTest
  @MethodSource("preparationFailures")
  @DisplayName("준비가 실패하면 같은 실패를 전파하고 잠금 보유 자식을 회수한다")
  void reapsHolderWhenPreparationFails(Throwable injected, @TempDir Path root) {
    // given
    try (var scenario = new CollectLockPreparationScenario(root)) {
      // when
      Throwable failure = scenario.failPreparation(injected);
      // then
      assertThat(failure).isSameAs(injected);
      assertThat(scenario.holderAlive()).isFalse();
    }
  }

  private static Stream<Throwable> preparationFailures() {
    return Stream.of(
        new IllegalStateException("injected lock preparation failure"),
        new AssertionError("injected lock preparation error"));
  }
}
