package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boardarchive.driver.FetchResult;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Opt in alongside a BoardDriverContract when fetchArchivedPost treats the archived Found.url as
 * observed. Each case uses a fresh driver that has not listed the post.
 */
@DisplayName("보관본 재확인은 목록 관측 없이 보관본 URL의 응답으로 판정한다 (BoardDriver)")
public interface ArchivedPostContract {
  @Test
  @DisplayName("관측하지 않은 글도 보관본 URL이 404이면 Deleted다")
  default void missingArchivedPostIsDeleted() {
    // given
    var contract = BoardDriverContract.requireBase(this);
    contract.givenPostStatus(404);
    // when
    var result =
        contract
            .scenario()
            .fetchArchived("a", contract.postId("a", 12), contract.postPath("a", 12));
    // then
    assertThat(result).isInstanceOf(FetchResult.Deleted.class);
  }

  @Test
  @DisplayName("관측하지 않은 글도 보관본 URL이 500이면 Unknown이다")
  default void failingArchivedPostIsUnknown() {
    // given
    var contract = BoardDriverContract.requireBase(this);
    contract.givenPostStatus(500);
    // when
    var result =
        contract
            .scenario()
            .fetchArchived("a", contract.postId("a", 12), contract.postPath("a", 12));
    // then
    assertThat(result).isInstanceOf(FetchResult.Unknown.class);
  }

  @Test
  @DisplayName("관측하지 않은 글도 보관본 URL이 200이면 그 URL의 Found다")
  default void archivedPostIsFoundAtArchivedUrl() {
    // given
    var contract = BoardDriverContract.requireBase(this);
    var s = contract.scenario();
    // when
    var result = s.fetchArchived("a", contract.postId("a", 12), contract.postPath("a", 12));
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f ->
                assertThat(f)
                    .extracting(FetchResult.Found::url, FetchResult.Found::title)
                    .containsExactly(s.url(contract.postPath("a", 12)), "First"));
  }

  @Test
  @DisplayName("관측하지 않은 글도 보관본 URL과 부속 경로만 각 한 번 요청한다")
  default void archivedPostRequestsArchivedUrl() {
    // given
    var contract = BoardDriverContract.requireBase(this);
    var s = contract.scenario();
    var expected = new ArrayList<String>();
    expected.add(contract.postPath("a", 12));
    expected.addAll(contract.companionPaths("a", 12));
    // when
    var requests =
        s.requestsDuring(
            () -> s.fetchArchived("a", contract.postId("a", 12), contract.postPath("a", 12)));
    // then
    assertThat(requests).containsExactlyElementsOf(expected);
  }
}
