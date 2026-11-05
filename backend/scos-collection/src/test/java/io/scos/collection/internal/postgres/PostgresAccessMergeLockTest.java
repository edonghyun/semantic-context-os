package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.AccessMerge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("접근 병합은 실패 원인을 보존하고 풀에 잠금을 남기지 않는다")
class PostgresAccessMergeLockTest {
  @Test
  @DisplayName("잠금 해제 장애가 점유 상실 예외를 가리지 않는다")
  void unlockFailureDoesNotMaskLostClaim() {
    try (var scenario = new AccessMergeLockScenario()) {
      // when·then
      assertThatThrownBy(scenario::mergeWithLostClaim).isSameAs(scenario.lost);
    }
  }

  @Test
  @DisplayName("실패한 병합의 연결이 풀로 돌아가도 run 잠금은 남지 않는다")
  void failedMergeDoesNotReturnLockedSession() {
    try (var scenario = new AccessMergeLockScenario()) {
      scenario.givenLostClaim();
      // when
      boolean available = scenario.runLockIsAvailable();
      // then
      assertThat(available).isTrue();
    }
  }

  @Test
  @DisplayName("관측 없는 INCOMPLETE 병합은 디렉터리를 다시 쓰지 않는다")
  void emptyIncompleteDoesNotRewriteDirectory() {
    try (var scenario = new AccessMergeLockScenario()) {
      scenario.rejectDirectoryWrites();
      // when
      AccessMerge applied = scenario.mergeEmptyIncomplete();
      // then
      assertThat(applied).isEqualTo(AccessMerge.INCOMPLETE);
    }
  }
}
