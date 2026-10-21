package io.scos.store.postgres.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.store.IndexedText;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 키워드 어댑터의 순수 함수(ADR-0014): 공백 제거 2-gram 계수(코드포인트 단위), 정규화, LIKE 이스케이프. DB 없음. */
@DisplayName("키워드 어댑터의 2-gram·정규화·LIKE 이스케이프는 순수 함수다(ADR-0014)")
class BigramsTest {
  @Test
  @DisplayName("2-gram은 공백을 지운 문자열에서 겹치게 등장 순서대로 센다")
  void bigramCountsOverlapAcrossWhitespace() {
    // when·then
    assertThat(Bigrams.bigramCounts("정산 정산"))
        .containsExactly(Map.entry("정산", 2), Map.entry("산정", 1));
  }

  @Test
  @DisplayName("코드포인트 단위라 아스트랄 문자 하나가 한 글자다")
  void astralCharacterIsOneCodePoint() {
    // when·then
    assertThat(Bigrams.bigramCounts("👨a")).containsExactly(Map.entry("👨a", 1));
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"a", " 　"})
  @DisplayName("공백을 지우고 1글자 이하면 2-gram이 없다")
  void noBigramUnderTwoCodePoints(String text) {
    // when·then
    assertThat(Bigrams.bigramCounts(text)).isEmpty();
  }

  @Test
  @DisplayName("정규화는 제목과 본문을 개행으로 이은 소문자다")
  void normalizeJoinsTitleAndContentLowercased() {
    // given
    IndexedText text = new IndexedText("u", "d", "Title", "Body X", "/", false);

    // when·then
    assertThat(Bigrams.normalize(text)).isEqualTo("title\nbody x");
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      value = {"a%b|a\\%b", "a_b|a\\_b", "a\\b|a\\\\b", "plain|plain"},
      delimiter = '|')
  @DisplayName("LIKE 메타문자(%·_·\\)는 이스케이프한다")
  void escapeLike(String in, String out) {
    // when·then
    assertThat(Bigrams.escapeLike(in)).isEqualTo(out);
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      value = {"a|a", "7|7", "정|정", "😀|😀", "\\|\\\\", "(|\\(", "%|\\%", ".|\\."},
      delimiter = '|')
  @DisplayName("정규식 리터럴은 ASCII 비영숫자만 역슬래시로 감싼다")
  void regexLiteral(String in, String out) {
    // when·then
    assertThat(Bigrams.regexLiteral(in.codePointAt(0))).isEqualTo(out);
  }

  @Test
  @DisplayName("ASCII 밖 첫 코드포인트(U+0080)는 역슬래시로 감싸지 않는다")
  void firstNonAsciiIsNotEscaped() {
    // when·then
    assertThat(Bigrams.regexLiteral(0x80)).isEqualTo(Character.toString(0x80));
  }
}
