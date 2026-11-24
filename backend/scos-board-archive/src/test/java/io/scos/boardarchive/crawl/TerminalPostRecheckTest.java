package io.scos.boardarchive.crawl;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.FetchResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("종료 상태 글은 변경 신호와 재확인 주기로 요청을 제한한다 (§4-2)")
class TerminalPostRecheckTest {
  private static final Duration ONE_HOUR = Duration.ofHours(1);
  @TempDir Path dir;
  CrawlScenario s;

  @BeforeEach
  void setup() throws Exception {
    s = new CrawlScenario(dir.resolve("archive.db"));
    s.givenFivePosts();
  }

  @AfterEach
  void close() {
    s.close();
  }

  @ParameterizedTest
  @MethodSource("terminalResults")
  @DisplayName("같은 신호로 계속 목록에 보이면 다음 실행들에서 다시 요청하지 않는다")
  void unchangedTerminalPostIsNotFetchedEveryRun(FetchResult result) {
    // given
    s.driver.result("3", result);
    s.run();
    if (!s.driver.fetched().contains("3"))
      throw new IllegalStateException("given 준비 실패: 최초 본문 판정 필요");
    // when
    var requests = s.fetchedPerRun(5, ONE_HOUR);
    // then
    assertThat(requests).containsExactly(List.of(), List.of(), List.of(), List.of(), List.of());
  }

  static Stream<FetchResult> terminalResults() {
    return Stream.of(new FetchResult.AccessDenied(), new FetchResult.Deleted());
  }

  @Test
  @DisplayName("접근 거부 뒤 신호가 바뀌면 다시 요청한다")
  void changedSignalRetriesTerminalPost() {
    // given
    s.driver.result("3", new FetchResult.AccessDenied());
    s.run();
    s.driver.clearFetched();
    s.driver.posts(
        "a",
        List.of(
            s.postAt("5", "00:05"), s.postAt("4", "00:04"), s.postWithCommentsAt("3", "00:03", 5)));
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("3");
  }

  @Test
  @DisplayName("신호 없는 접근 거부 글은 마지막 판정에서 7일 뒤 다시 요청한다")
  void noSignalRetriesAfterSevenDays() {
    // given
    s.driver.posts("a", List.of(s.postWithoutSignalAt("3", "00:03")));
    s.driver.result("3", new FetchResult.AccessDenied());
    s.run();
    s.daysLater(6);
    s.driver.clearFetched();
    s.run();
    if (!s.driver.fetched().isEmpty())
      throw new IllegalStateException("given 준비 실패: 7일 전에는 재요청 금지");
    s.daysLater(1);
    // when
    s.run();
    // then
    assertThat(s.driver.fetched()).containsExactly("3");
  }
}
