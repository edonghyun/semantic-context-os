package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boardarchive.driver.FetchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Opt in alongside a BoardDriverContract that declares nonempty companionPaths. */
@DisplayName("부속 요청 실패는 본문을 보존하고 댓글을 불완전으로 표시한다 (BoardDriver)")
public interface CompanionRequestsContract {
  @Test
  @DisplayName("부속 경로가 500이면 본문을 보존하며 댓글은 불완전하다")
  default void failedCompanionKeepsPostWithIncompleteComments() {
    // given
    var contract = BoardDriverContract.requireBase(this);
    var s = contract.scenario();
    s.givenObservedPost("a", contract.postId("a", 12));
    var companions = contract.companionPaths("a", 12);
    if (companions.isEmpty())
      throw new IllegalStateException("given 준비 실패: 부속 요청 계약에는 부속 경로가 있어야 한다");
    s.givenResponse(companions.getFirst(), 500);
    // when
    var result = s.fetch("a", contract.postId("a", 12));
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f ->
                assertThat(f)
                    .extracting(
                        FetchResult.Found::url,
                        FetchResult.Found::title,
                        FetchResult.Found::commentsComplete)
                    .containsExactly(s.url(contract.postPath("a", 12)), "First", false));
  }
}
