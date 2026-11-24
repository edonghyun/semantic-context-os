package io.scos.boardarchive.driver;

import io.scos.boardarchive.testkit.ArchivedPostContract;
import io.scos.boardarchive.testkit.CategorizedBoardsContract;
import io.scos.boardarchive.testkit.LocalSite;
import io.scos.boardarchive.testkit.MutableClock;
import io.scos.boardarchive.testkit.PagedBoardDriverContract;
import io.scos.boardarchive.testkit.StaticBoardsContract;
import io.scos.boardarchive.testsupport.ScriptedDriver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;

@DisplayName("스크립트 대역은 게시판 드라이버 계약을 지킨다 (BoardDriver)")
class ScriptedDriverContractTest extends PagedBoardDriverContract
    implements CategorizedBoardsContract, StaticBoardsContract, ArchivedPostContract {
  private ScriptedDriver fake;

  @Override
  protected String postId(String board, int number) {
    return board + "-" + number;
  }

  @Override
  protected BoardDriver createDriver(LocalSite site, MutableClock clock) {
    fake = new ScriptedDriver(site, clock);
    fake.board(new Board("a", "General", "Public"));
    fake.board(new Board("b", "Other", "Archive"));
    fake.posts(
        "a",
        List.of(
            summary("a-14", "Notice", "2026-11-14T15:00:00Z", true),
            summary("a-12", "First", "2026-11-16T15:00:00Z", false),
            summary("a-11", "Second", "2026-11-15T15:00:00Z", false),
            summary("a-10", "Third", "2026-11-13T15:00:00Z", false)));
    fake.posts("b", List.of(summary("b-12", "Other first", "2026-11-16T15:00:00Z", false)));
    return fake;
  }

  @Override
  protected String listPath(String board) {
    return "/list/" + board + "?offset=0";
  }

  @Override
  protected String postPath(String board, int number) {
    return "/posts/" + board + "-" + number;
  }

  @Override
  protected void givenListStatus(int status) {
    fake.listStatus("a", status);
  }

  @Override
  protected void givenPostStatus(int status) {
    fake.postStatus("a-12", status);
  }

  private PostSummary summary(String id, String title, String at, boolean pinned) {
    return new PostSummary(id, title, Instant.parse(at), Optional.empty(), pinned);
  }
}
