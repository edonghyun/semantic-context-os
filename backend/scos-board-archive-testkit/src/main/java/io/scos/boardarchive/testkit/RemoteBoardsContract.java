package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.HttpStatusException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Opt in alongside a BoardDriverContract when board discovery reads a remote menu. */
@DisplayName("원격 게시판은 조회 실패를 예외로 전파한다 (BoardDriver)")
public interface RemoteBoardsContract {
  /** Origin-relative fixture menu path, including its query. */
  String boardsPath();

  @ParameterizedTest(name = "{0}")
  @MethodSource("fetcherFailures")
  @DisplayName("게시판 요청이 예외로 끝나면 같은 예외를 던진다")
  default void boardsPropagateFetcherFailure(RuntimeException failure) {
    // given
    var s = BoardDriverContract.requireBase(this).scenario();
    s.givenFetcherFailure(failure);
    // when·then
    assertThatThrownBy(s::boards).isSameAs(failure);
  }

  @Test
  @DisplayName("게시판 목록이 500이면 상태 코드를 담은 예외를 던진다")
  default void boardFailureIncludesStatus() {
    // given
    var s = BoardDriverContract.requireBase(this).scenario();
    s.givenResponse(boardsPath(), 500);
    // when·then
    assertThatThrownBy(s::boards)
        .isInstanceOfSatisfying(
            HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(500));
  }
}
