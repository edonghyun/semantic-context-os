package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.THROWABLE;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("stdio 세션의 준비 실패는 소유 자원을 정리한다(T12-I2)")
class ScosStdioSessionTest {
  @TempDir Path root;

  static Stream<Throwable> preparationFailures() {
    return Stream.of(new IllegalStateException("준비 실패"), new AssertionError("준비 실패"));
  }

  @ParameterizedTest
  @MethodSource("preparationFailures")
  @DisplayName("준비가 실패하면 원래 실패를 그대로 던진다")
  void rethrowsPreparationFailure(Throwable failure) throws Exception {
    // given
    try (var scenario = new ScosStdioPreparationScenario(root)) {
      // when
      Throwable thrown = scenario.failPreparation(failure);
      // then
      assertThat(thrown).isSameAs(failure);
    }
  }

  @ParameterizedTest
  @MethodSource("preparationFailures")
  @DisplayName("준비가 실패하면 막힌 수집을 끝내고 잠금을 놓는다")
  void releasesCollectionLock(Throwable failure) throws Exception {
    // given
    try (var scenario = new ScosStdioPreparationScenario(root)) {
      // when
      scenario.failPreparation(failure);
      // then
      assertThat(scenario.lockAvailable()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("preparationFailures")
  @DisplayName("준비가 실패하면 소유 실행기를 종료한다")
  void terminatesRunner(Throwable failure) throws Exception {
    // given
    try (var scenario = new ScosStdioPreparationScenario(root)) {
      // when
      scenario.failPreparation(failure);
      // then
      assertThat(scenario.runnerTerminated()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("preparationFailures")
  @DisplayName("정리 중 실패는 준비 실패에 suppressed로 남긴다")
  void retainsCleanupFailure(Throwable failure) throws Exception {
    // given
    try (var scenario = ScosStdioPreparationScenario.failedEnvironmentRead(root)) {
      // when
      Throwable thrown = scenario.failPreparation(failure);
      // then
      assertThat(thrown.getSuppressed())
          .singleElement(THROWABLE)
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("injected environment read failure: SCOS_HOME");
    }
  }

  @ParameterizedTest
  @MethodSource("preparationFailures")
  @DisplayName("종료 대기가 실패해도 소유 실행기를 종료한다")
  void terminatesRunnerAfterCleanupFailure(Throwable failure) throws Exception {
    // given
    try (var scenario = ScosStdioPreparationScenario.failedEnvironmentRead(root)) {
      // when
      scenario.failPreparation(failure);
      // then
      assertThat(scenario.runnerTerminated()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("preparationFailures")
  @DisplayName("인터럽트된 준비가 실패해도 수집 잠금을 놓는다")
  void releasesCollectionLockAfterInterruptedPreparation(Throwable failure) throws Exception {
    // given
    try (var scenario = new ScosStdioPreparationScenario(root)) {
      // when
      scenario.interruptPreparation(failure);
      // then
      assertThat(scenario.lockAvailable()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("preparationFailures")
  @DisplayName("준비 실패를 정리한 뒤 호출자의 인터럽트 표시를 복원한다")
  void restoresInterruptedFlag(Throwable failure) throws Exception {
    // given
    try (var scenario = new ScosStdioPreparationScenario(root)) {
      // when
      scenario.interruptPreparation(failure);
      // then
      assertThat(scenario.interruptedAfterPreparation()).isTrue();
    }
  }
}
