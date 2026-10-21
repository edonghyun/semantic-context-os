package io.scos.engine.support.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** JS 공백 집합(CONTRACTS F-1)을 정규식에 끼워 쓸 때의 동작. 집합의 값 자체는 TextDeterminismTest가 fixture로 검증한다. */
@DisplayName("JS 공백 집합은 정규식 문자 클래스로 끼워 쓸 수 있다(CONTRACTS F-1)")
class TextTest {
  // 정규식은 테스트마다 새로 만든다 — static 상수로 두면 클래스 초기화 때 한 번만 Text를 불러 변이를 놓친다.
  static Pattern whitespaceRun() {
    return Pattern.compile(Text.whitespaceClass() + "+");
  }

  static Pattern nonWhitespaceRun() {
    return Pattern.compile("[^" + Text.whitespaceChars() + "]+");
  }

  @Test
  @DisplayName("whitespaceClass+는 공백·NBSP·U+3000·BOM·탭의 연속과 일치한다")
  void whitespaceClassMatchesJsWhitespaceRun() {
    // when
    boolean matches = whitespaceRun().matcher(" \u00a0\u3000\ufeff \t").matches();

    // then
    assertThat(matches).isTrue();
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"a", ""})
  @DisplayName("whitespaceClass+는 글자나 빈 문자열과 일치하지 않는다")
  void whitespaceClassRejectsNonWhitespace(String text) {
    // when
    boolean matches = whitespaceRun().matcher(text).matches();

    // then
    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("whitespaceChars를 넣은 부정 클래스는 글자 연속과 일치한다")
  void whitespaceCharsFitsInsideNegatedClass() {
    // when
    boolean matches = nonWhitespaceRun().matcher("abc").matches();

    // then
    assertThat(matches).isTrue();
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"a b", "a\u00a0b", "a\u3000b"})
  @DisplayName("부정 클래스는 공백·NBSP·U+3000이 낀 글자열과 일치하지 않는다")
  void negatedClassStopsAtWhitespace(String text) {
    // when
    boolean matches = nonWhitespaceRun().matcher(text).matches();

    // then
    assertThat(matches).isFalse();
  }
}
