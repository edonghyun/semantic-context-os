package io.scos.engine.support.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("부분 문자열과 trigram의 점수 정책을 공유한다(B5)")
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KeywordScoreTest {
  @ParameterizedTest(name = "{0} / {1}")
  @DisplayName("세 글자 이상 정확 표기의 점수는 겹치지 않는 등장 횟수로 정한다")
  @CsvSource({
    "abc, abc, abc, 0.75",
    "abc, abcabcabc, abcabcabc, 0.875",
    "aaa, aaaa, aaaa, 0.75",
    "😀ab, 😀ab, 😀ab, 0.75"
  })
  void scoresExactOccurrences(String token, String text, String compact, double expected) {
    // given
    KeywordScore score = KeywordScore.of(List.of(token));
    // when
    double got = score.score(text, compact);
    // then
    assertThat(got).isEqualTo(expected);
  }

  @Test
  @DisplayName("보충 평면 문자를 포함해 두 글자인 토큰은 짧은 토큰 점수를 받는다")
  void countsCodePointsAtShortTokenBoundary() {
    // given
    KeywordScore score = KeywordScore.of(List.of("😀a"));
    // when
    double got = score.score("😀a", "😀a");
    // then
    assertThat(got).isEqualTo(0.5);
  }

  @Test
  @DisplayName("어느 trigram도 맞지 않으면 점수는 영이다")
  void missingTrigramsScoreZero() {
    // given
    KeywordScore score = KeywordScore.of(List.of("abc"));
    // when
    double got = score.score("xyz", "xyz");
    // then
    assertThat(got).isEqualTo(0.0);
  }

  @Test
  @DisplayName("정확 표기가 붙여쓰기 매치보다 높다")
  void exactBeatsCompact() {
    // given
    KeywordScore score = KeywordScore.of(List.of("검색강도"));

    // when
    double exact = score.score("검색강도 작업", "검색강도작업");
    double spaced = score.score("검색 강도 작업", "검색강도작업");

    // then
    assertThat(exact).isGreaterThan(spaced);
  }

  @Test
  @DisplayName("한국어 2글자 토큰은 등장 횟수로 점수를 받는다")
  void twoLetterKorean() {
    // given
    KeywordScore score = KeywordScore.of(List.of("정산"));

    // when
    double got = score.score("정산 정산", "정산정산");

    // then
    assertThat(got).isEqualTo(2.0 / 3);
  }

  @Test
  @DisplayName("3글자 이상 토큰은 trigram 하나만 공백 제거 본문에 있어도 0.25다")
  void trigramOrIsQuarter() {
    // given
    KeywordScore score = KeywordScore.of(List.of("스냅샷보관"));

    // when
    double got = score.score("스냅샷은 30일", "스냅샷은30일");

    // then
    assertThat(got).isEqualTo(0.25);
  }
}
