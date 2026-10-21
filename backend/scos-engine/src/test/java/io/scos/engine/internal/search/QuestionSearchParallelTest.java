package io.scos.engine.internal.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** search_context 레시피의 키워드 채널 ∥ 임베딩(DESIGN-PATTERNS §4, F3 T3.3). 순서·점수는 QuestionSearchTest가 본다. */
@DisplayName("질문 검색은 키워드 채널과 임베딩을 함께 돌린다(DESIGN-PATTERNS §4)")
class QuestionSearchParallelTest {
  final QuestionSearchScenario scenario = new QuestionSearchScenario();

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("키워드 검색과 임베딩은 동시에 진행된다")
  void keywordAndEmbeddingOverlap() {
    // given
    scenario.givenChannelsWaitForEachOther();

    // when
    scenario.search();

    // then
    assertThat(scenario.channelsOverlapped()).isTrue();
  }

  static Stream<Throwable> keywordFailures() {
    return Stream.of(new IllegalStateException("키워드 색인 장애"), new AssertionError("키워드 색인 Error"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("keywordFailures")
  @DisplayName("키워드 색인의 실패는 감싸지지 않고 그대로 전파된다")
  void keywordFailurePropagatesUnwrapped(Throwable failure) {
    // given
    scenario.givenKeywordIndexThrows(failure);

    // when
    Throwable thrown = catchThrowable(scenario::search);

    // then
    assertThat(thrown).isSameAs(failure);
  }

  @Nested
  @DisplayName("두 채널이 다 실패하면")
  class BothChannelsFail {
    RuntimeException keywordFailure;

    @BeforeEach
    void bothFail() {
      keywordFailure = scenario.givenBothChannelsFail();
    }

    @Test
    @DisplayName("순차 실행처럼 키워드 실패가 전파된다")
    void keywordFailureWinsWhenBothChannelsFail() {
      // when
      Throwable thrown = catchThrowable(scenario::search);

      // then
      assertThat(thrown).isSameAs(keywordFailure);
    }

    @Test
    @DisplayName("임베더 실패는 키워드 실패에 덧붙는다")
    void embedderFailureIsSuppressedByKeywordFailure() {
      // when
      Throwable thrown = catchThrowable(scenario::search);

      // then
      assertThat(thrown.getSuppressed()).containsExactly(scenario.embedderFailure());
    }
  }

  @Test
  @DisplayName("키워드 채널이 이미 실패했으면 그 실패가 전파되고, 임베더를 부르지 않는다")
  void keywordFailureBeforeEmbeddingSkipsEmbedder() {
    // given
    scenario.givenKeywordChannelOnCallerThread();
    RuntimeException failure = scenario.givenKeywordIndexFails();

    // when
    Throwable thrown = catchThrowable(scenario::search);

    // then
    assertThat(thrown).isSameAs(failure);
    assertThat(scenario.embedCalls()).isZero();
  }

  @Test
  @DisplayName("임베더가 실패하면 키워드 검색이 끝난 뒤에 그 실패를 던진다")
  void embedderFailureWaitsForKeywordSearch() {
    // given
    RuntimeException failure = scenario.givenKeywordFinishesOnlyAfterEmbedderFails();

    // when
    Throwable thrown = catchThrowable(scenario::search);

    // then
    assertThat(thrown).isSameAs(failure);
    assertThat(scenario.keywordFinished()).isTrue();
  }
}
