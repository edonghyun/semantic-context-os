package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.*;
import java.net.ConnectException;
import java.net.http.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;

@DisplayName("드라이버 시나리오는 생성 실패 시 연 자원을 정리한다 (BoardDriver)")
class BoardDriverScenarioTest {
  private final RuntimeException failure =
      new IllegalStateException("injected board discovery failure");
  private LocalSite site;

  @AfterEach
  void closeFixture() {
    if (site != null) site.close();
  }

  @Test
  @DisplayName("게시판 준비 실패는 사이트를 닫고 같은 예외를 전파한다")
  void failedBoardDiscoveryClosesSiteAndPreservesFailure() {
    // given
    try (var client = HttpClient.newHttpClient()) {
      // when
      Throwable thrown = catchThrowable(() -> new BoardDriverScenario(this::failingDriver));
      // then
      assertThat(thrown).isSameAs(failure);
      var request = HttpRequest.newBuilder(site.uri("/probe")).GET().build();
      assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.discarding()))
          .isInstanceOf(ConnectException.class);
    }
  }

  private BoardDriver failingDriver(LocalSite fixture, MutableClock clock) {
    site = fixture;
    site.respond("/probe", 200, "fixture");
    return new BoardDriver() {
      @Override
      public String site() {
        return "fixture";
      }

      @Override
      public List<Board> boards(Fetcher http) {
        throw failure;
      }

      @Override
      public ListPage listPosts(Fetcher http, Board board, Optional<String> cursor) {
        throw new UnsupportedOperationException("No list during board discovery");
      }

      @Override
      public FetchResult fetchPost(Fetcher http, Board board, String id) {
        throw new UnsupportedOperationException("No fetch during board discovery");
      }
    };
  }
}
