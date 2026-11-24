package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boardarchive.driver.PostSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;

/** Adds portable continuation and terminal-page requirements to the base contract. */
@DisplayName("쪽 넘김 드라이버는 저장한 커서로 목록을 이어 읽는다 (BoardDriver)")
public abstract class PagedBoardDriverContract extends BoardDriverContract {
  @Test
  @DisplayName("다음 쪽이 있으면 이어 읽을 커서가 있다")
  protected final void firstPageHasContinuation() {
    // when
    var page = scenario().firstPage("a");
    // then
    assertThat(page.nextCursor()).isPresent();
  }

  @Nested
  @DisplayName("첫 쪽에서 다음 커서를 받으면")
  final class AfterFirstPage {
    String cursor;
    List<String> firstPageIds;

    @BeforeEach
    final void readFirstPage() {
      var first = scenario().firstPage("a");
      firstPageIds = first.posts().stream().map(PostSummary::postId).toList();
      cursor =
          first
              .nextCursor()
              .orElseThrow(() -> new IllegalStateException("given 준비 실패: 첫 쪽에 다음 커서가 있어야 한다"));
    }

    @Test
    @DisplayName("그 커서로 읽으면 사이트 안에서 id가 겹치지 않는 다음 글이다")
    final void cursorReadsNextPosts() {
      // given
      var other = scenario().firstPage("b");
      if (other.posts().isEmpty())
        throw new IllegalStateException("given 준비 실패: 다른 게시판의 글이 있어야 한다");
      // when
      var page = scenario().nextPage("a", cursor);
      // then
      assertThat(page.posts())
          .containsExactly(
              new PostSummary(
                  postId("a", 10),
                  "Third",
                  Instant.parse("2026-11-13T15:00:00Z"),
                  Optional.empty(),
                  false));
      assertThat(page.posts())
          .extracting(PostSummary::postId)
          .doesNotContainAnyElementsOf(firstPageIds)
          .doesNotContainAnyElementsOf(other.posts().stream().map(PostSummary::postId).toList());
    }

    @Test
    @DisplayName("저장한 커서를 새 드라이버로 읽어도 같은 다음 글을 돌려준다")
    final void savedCursorWorksWithNewDriver() {
      // given
      scenario().givenNewDriver();
      // when
      var page = scenario().nextPage("a", cursor);
      // then
      assertThat(page.posts())
          .containsExactly(
              new PostSummary(
                  postId("a", 10),
                  "Third",
                  Instant.parse("2026-11-13T15:00:00Z"),
                  Optional.empty(),
                  false));
    }

    @Test
    @DisplayName("마지막 쪽에는 다음 커서가 없다")
    final void lastPageHasNoContinuation() {
      // when
      var page = scenario().nextPage("a", cursor);
      // then
      assertThat(page.nextCursor()).isEmpty();
    }
  }
}
