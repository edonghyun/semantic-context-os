package io.scos.boardarchive.driver;

import io.scos.boardarchive.testkit.CategorizedBoardsContract;
import io.scos.boardarchive.testkit.LocalSite;
import io.scos.boardarchive.testkit.MutableClock;
import io.scos.boardarchive.testkit.PagedBoardDriverContract;
import io.scos.boardarchive.testkit.StaticBoardsContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("JSON 설정형 드라이버는 게시판 계약을 지킨다 (BoardDriver)")
class ProfileDriverJsonContractTest extends PagedBoardDriverContract
    implements CategorizedBoardsContract, StaticBoardsContract {
  @Override
  protected String postId(String board, int number) {
    return board + "-" + number;
  }

  @Override
  protected BoardDriver createDriver(LocalSite site, MutableClock clock) {
    return ProfileDriverFixtures.createJson(site, clock);
  }

  @Override
  protected String listPath(String board) {
    return "/list/" + board + "?offset=0";
  }

  @Override
  protected String postPath(String board, int number) {
    return "/posts/" + board + "-" + number;
  }
}
