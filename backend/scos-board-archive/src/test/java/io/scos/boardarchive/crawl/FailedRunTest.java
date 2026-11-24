package io.scos.boardarchive.crawl;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("발견 실패는 기록을 마감하고 정상 실행의 훑기 주기를 보존한다 (§4)")
class FailedRunTest {
  @TempDir Path dir;
  CrawlScenario s;

  @BeforeEach
  void setup() throws Exception {
    s = new CrawlScenario(dir.resolve("archive.db"));
    s.driver.boardFailure(new IllegalArgumentException("bad board list"));
  }

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("발견 예외를 전달한 실행은 실패 사유와 마감 상태를 남긴다")
  void discoveryFailureIsRecorded() throws Exception {
    // when·then
    assertThatThrownBy(() -> s.run())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("bad board list");
    assertThat(s.archive.lastRun())
        .extracting(r -> r.finished(), r -> r.failureReason())
        .containsExactly(true, Optional.of("IllegalArgumentException"));
  }

  @Test
  @DisplayName("발견이 9번 실패해도 다음 정상 실행은 훑기 주기가 아니다")
  void failedDiscoveriesDoNotAdvanceSweepSchedule() {
    // given
    givenNineFailedDiscoveries();
    s.driver.clearBoardFailure();
    // when
    var stats = s.run();
    // then
    assertThat(stats.sweepCompared()).isFalse();
  }

  private void givenNineFailedDiscoveries() {
    for (int i = 0; i < 9; i++) {
      try {
        s.run();
      } catch (IllegalArgumentException failure) {
        if ("bad board list".equals(failure.getMessage())) continue;
        throw new IllegalStateException("given 준비 실패: 발견 실패 사유가 다르다", failure);
      }
      throw new IllegalStateException("given 준비 실패: 발견이 실패해야 한다");
    }
  }
}
