package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("그룹·신원은 실제 값과 관측 시각이 같으면 재작성하지 않는다(P-2)")
class DirectoryWriteTest {
  @Test
  @DisplayName("같은 디렉터리 관측의 행 쓰기는 0이다")
  void sameObservationWritesNoRows() {
    try (var scenario = new DirectoryWriteScenario()) {
      scenario.sameObservation();
      // then
      assertThat(scenario.writes()).isZero();
    }
  }

  @Test
  @DisplayName("같은 권한의 새 관측 시각은 만료 고수위를 갱신한다")
  void newerObservationStillWritesTimestamps() {
    try (var scenario = new DirectoryWriteScenario()) {
      scenario.newerObservation();
      // then
      assertThat(scenario.writes()).isEqualTo(2);
    }
  }
}
