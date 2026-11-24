package io.scos.boardarchive.testkit.consumer;

import io.scos.boardarchive.testkit.SinglePageBoardDriverContract;
import org.junit.jupiter.api.DisplayName;

/** Shared fixture identity mapping; each consumer chooses its own independent contracts. */
@DisplayName("숫자 id 소비자는 자기 경로에서 같은 기본 사례를 검증한다 (BoardDriver)")
abstract class NumericBoardDriverContract extends SinglePageBoardDriverContract {
  @Override
  protected final String postId(String board, int number) {
    return (board.equals("a") ? "1" : "2") + number;
  }

  @Override
  protected final String listPath(String board) {
    return "/boards/" + board + "/posts?page=1";
  }

  @Override
  protected final String postPath(String board, int number) {
    return "/view/" + postId(board, number);
  }
}
