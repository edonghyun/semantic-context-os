package io.scos.boardarchive.testkit.consumer;

import io.scos.boardarchive.driver.BoardDriver;
import io.scos.boardarchive.testkit.CompanionRequestsContract;
import io.scos.boardarchive.testkit.LocalSite;
import io.scos.boardarchive.testkit.MutableClock;
import io.scos.boardarchive.testkit.RemoteBoardsContract;
import java.util.List;
import org.junit.jupiter.api.DisplayName;

@DisplayName("원격 게시판 드라이버는 조회한 게시판으로 수집한다 (BoardDriver)")
class RemoteBoardsContractTest extends NumericBoardDriverContract
    implements RemoteBoardsContract, CompanionRequestsContract {
  @Override
  protected List<String> companionPaths(String board, int number) {
    return List.of("/comments/" + postId(board, number) + "?page=1");
  }

  @Override
  public String boardsPath() {
    return "/boards";
  }

  @Override
  protected BoardDriver createDriver(LocalSite site, MutableClock clock) {
    site.respond(boardsPath(), 200, "configured boards");
    for (String board : List.of("a", "b"))
      for (String path : companionPaths(board, 12)) site.respond(path, 200, "[]");
    return new RemoteBoardsDriver(NumericProfileFixture.create(site, clock), site);
  }
}
