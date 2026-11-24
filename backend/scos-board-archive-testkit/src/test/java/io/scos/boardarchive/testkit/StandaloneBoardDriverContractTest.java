package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.fetch.TransportFailureException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("선택 계약은 기본 계약 없이 쓰면 준비 원인을 알린다 (BoardDriver)")
class StandaloneBoardDriverContractTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("standaloneCases")
  @DisplayName("기본 계약 없이 선택 사례를 실행하면 준비 실패다")
  void standaloneCaseFailsPreparation(String name, Runnable action) {
    // when
    Throwable failure = catchThrowable(action::run);
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: 선택 계약은 BoardDriverContract 하위 클래스에서 구현한다");
  }

  static Stream<Arguments> standaloneCases() {
    var categories = new CategorizedBoardsContract() {};
    var companions = new CompanionRequestsContract() {};
    var staticBoards = new StaticBoardsContract() {};
    var remoteBoards =
        new RemoteBoardsContract() {
          @Override
          public String boardsPath() {
            return "/boards";
          }
        };
    var failure = new TransportFailureException("injected transport failure");
    return Stream.of(
        Arguments.of("분류", (Runnable) categories::returnsBoardCategories),
        Arguments.of(
            "부속 요청", (Runnable) companions::failedCompanionKeepsPostWithIncompleteComments),
        Arguments.of("정적 게시판", (Runnable) () -> staticBoards.staticBoardsDoNotUseFetcher(failure)),
        Arguments.of("원격 상태", (Runnable) remoteBoards::boardFailureIncludesStatus),
        Arguments.of(
            "원격 예외", (Runnable) () -> remoteBoards.boardsPropagateFetcherFailure(failure)));
  }
}
