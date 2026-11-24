package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.BoardDriver;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("선택 계약의 누락과 재정의는 준비 단계에서 거부한다 (BoardDriver)")
class BoardDriverContractSelectionTest {
  @Test
  @DisplayName("게시판 조회 계약을 고르지 않으면 준비 실패다")
  void missingBoardSelectionFailsPreparation() {
    // given
    var contract = new Unselected() {};
    // when
    Throwable failure = catchThrowable(() -> prepare(contract));
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: StaticBoardsContract와 RemoteBoardsContract 중 정확히 하나를 선택한다");
  }

  @Test
  @DisplayName("게시판 조회 계약을 둘 다 고르면 준비 실패다")
  void conflictingBoardSelectionFailsPreparation() {
    // given
    var contract = new BothBoardSelections() {};
    // when
    Throwable failure = catchThrowable(() -> prepare(contract));
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: StaticBoardsContract와 RemoteBoardsContract 중 정확히 하나를 선택한다");
  }

  @Test
  @DisplayName("부속 경로를 선언하고 계약을 빠뜨리면 준비 실패다")
  void missingCompanionSelectionFailsPreparation() {
    // given
    var contract = new MissingCompanionSelection() {};
    // when
    Throwable failure = catchThrowable(() -> prepare(contract));
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: 부속 경로 선언과 CompanionRequestsContract 선택이 일치해야 한다");
  }

  @Test
  @DisplayName("부속 경로 없이 계약을 고르면 준비 실패다")
  void emptyCompanionSelectionFailsPreparation() {
    // given
    var contract = new EmptyCompanionSelection() {};
    // when
    Throwable failure = catchThrowable(() -> prepare(contract));
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: 부속 경로 선언과 CompanionRequestsContract 선택이 일치해야 한다");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("overriddenCases")
  @DisplayName("선택 사례를 재정의하면 준비 실패다")
  void overriddenCaseFailsPreparation(String method, BoardDriverContract contract) {
    // when
    Throwable failure = catchThrowable(() -> prepare(contract));
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: 선택 계약 사례 " + method + "를 재정의했다");
  }

  static Stream<Arguments> overriddenCases() {
    return Stream.of(
        Arguments.of("returnsBoardCategories", new OverriddenCategories() {}),
        Arguments.of("returnsBoardCategories", new AnnotatedCategories() {}),
        Arguments.of("staticBoardsDoNotUseFetcher", new OverriddenStaticBoards() {}),
        Arguments.of("boardsPropagateFetcherFailure", new OverriddenRemoteFailure() {}),
        Arguments.of("boardFailureIncludesStatus", new OverriddenRemoteStatus() {}),
        Arguments.of(
            "failedCompanionKeepsPostWithIncompleteComments", new OverriddenCompanion() {}),
        Arguments.of("missingArchivedPostIsDeleted", new OverriddenArchivedPost() {}));
  }

  private void prepare(BoardDriverContract contract) throws Exception {
    try {
      contract.setup();
    } finally {
      contract.close();
    }
  }

  abstract static class Unselected extends BoardDriverContract {
    @Override
    protected BoardDriver createDriver(LocalSite site, MutableClock clock) {
      throw new IllegalStateException("driver construction reached");
    }

    @Override
    protected String postId(String board, int number) {
      return board + "-" + number;
    }

    @Override
    protected String listPath(String board) {
      return "/list/" + board;
    }

    @Override
    protected String postPath(String board, int number) {
      return "/posts/" + postId(board, number);
    }
  }

  abstract static class BothBoardSelections extends RemoteSelection
      implements StaticBoardsContract {}

  abstract static class RemoteSelection extends Unselected implements RemoteBoardsContract {
    @Override
    public String boardsPath() {
      return "/boards";
    }
  }

  abstract static class StaticSelection extends Unselected implements StaticBoardsContract {}

  abstract static class MissingCompanionSelection extends StaticSelection {
    @Override
    protected List<String> companionPaths(String board, int number) {
      return List.of("/comments/" + postId(board, number));
    }
  }

  abstract static class EmptyCompanionSelection extends StaticSelection
      implements CompanionRequestsContract {}

  abstract static class OverriddenCategories extends StaticSelection
      implements CategorizedBoardsContract {
    @Override
    public void returnsBoardCategories() {}
  }

  abstract static class AnnotatedCategories extends StaticSelection
      implements CategorizedBoardsContract {
    @Override
    @Test
    @DisplayName("분류 사례를 재정의한 소비자 표본이다")
    public void returnsBoardCategories() {
      // then
    }
  }

  abstract static class OverriddenStaticBoards extends StaticSelection {
    @Override
    public void staticBoardsDoNotUseFetcher(RuntimeException failure) {}
  }

  abstract static class OverriddenRemoteFailure extends RemoteSelection {
    @Override
    public void boardsPropagateFetcherFailure(RuntimeException failure) {}
  }

  abstract static class OverriddenRemoteStatus extends RemoteSelection {
    @Override
    public void boardFailureIncludesStatus() {}
  }

  abstract static class OverriddenCompanion extends MissingCompanionSelection
      implements CompanionRequestsContract {
    @Override
    public void failedCompanionKeepsPostWithIncompleteComments() {}
  }

  abstract static class OverriddenArchivedPost extends StaticSelection
      implements ArchivedPostContract {
    @Override
    public void missingArchivedPostIsDeleted() {}
  }
}
