package io.scos.boardarchive.crawl;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("과거 채우기 재개 전에 최근 변경을 재확인한다 (§4-2)")
class BackfillRecheckTest {
  private static final Duration POST_COST = Duration.ofSeconds(60);
  private static final Duration RUN_BUDGET = Duration.ofSeconds(100);
  private static final Duration ONE_HOUR = Duration.ofHours(1);
  @TempDir Path dir;
  CrawlScenario s;

  @BeforeEach
  void setup() throws Exception {
    s = new CrawlScenario(dir.resolve("archive.db"));
    s.driver.posts(
        "a",
        List.of(
            s.postAt("9", "00:09"),
            s.postAt("8", "00:08"),
            s.postAt("7", "00:07"),
            s.postAt("6", "00:06"),
            s.postAt("5", "00:05"),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    s.driver.pageSize(3);
    s.driver.postCost(POST_COST);
    s.settings = CrawlSettings.defaults(RUN_BUDGET);
    s.run();
    if (!s.driver.fetched().equals(List.of("9", "8")))
      throw new IllegalStateException("given 준비 실패: 앞쪽 두 글을 먼저 보관한다");
    s.driver.clearFetched();
    s.driver.posts(
        "a",
        List.of(
            s.postAt("9", "00:09"),
            s.postWithCommentsAt("8", "00:08", 5),
            s.postAt("7", "00:07"),
            s.postAt("6", "00:06"),
            s.postAt("5", "00:05"),
            s.postAt("4", "00:04"),
            s.postAt("3", "00:03"),
            s.postAt("2", "00:02"),
            s.postAt("1", "00:01")));
    s.archive.clock.advance(ONE_HOUR);
  }

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("창 안 댓글 수가 바뀌면 과거 채우기보다 먼저 그 실행에서 수집한다")
  void changedPostIsRecheckedBeforeResumingHistory() {
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("8", "7");
  }
}
