package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boardarchive.driver.Board;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Opt in alongside a BoardDriverContract when the fixture supplies board categories. */
@DisplayName("분류 있는 게시판은 원격 또는 설정의 분류를 전달한다 (BoardDriver)")
public interface CategorizedBoardsContract {
  @Test
  @DisplayName("게시판의 분류는 Public과 Archive 순서다")
  default void returnsBoardCategories() {
    // when
    var boards = BoardDriverContract.requireBase(this).scenario().boards();
    // then
    assertThat(boards).extracting(Board::category).containsExactly("Public", "Archive");
  }
}
