package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.Board;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Opt in alongside a BoardDriverContract when board discovery makes no HTTP requests. */
@DisplayName("정적 게시판은 HTTP 없이 설정한 목록을 돌려준다 (BoardDriver)")
public interface StaticBoardsContract {
  @ParameterizedTest(name = "{0}")
  @MethodSource("fetcherFailures")
  @DisplayName("Fetcher가 실패해도 정적 게시판 목록을 돌려준다")
  default void staticBoardsDoNotUseFetcher(RuntimeException failure) {
    // given
    var s = BoardDriverContract.requireBase(this).scenario();
    s.givenFetcherFailure(failure);
    // when
    var boards = s.boards();
    // then
    assertThat(boards)
        .extracting(Board::id, Board::name)
        .containsExactly(tuple("a", "General"), tuple("b", "Other"));
  }
}
