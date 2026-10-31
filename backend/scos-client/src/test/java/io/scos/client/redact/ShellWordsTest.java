package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("셸 낱말 읽기는 공백과 따옴표 경계를 보존한다")
class ShellWordsTest {
  @ParameterizedTest
  @ValueSource(strings = {"'", "\"", "'open", "\"open"})
  @DisplayName("닫는 따옴표가 없으면 따옴표 내부 범위가 없다")
  void unmatchedQuoteHasNoInside(String text) {
    // when
    Optional<ShellWords.Word> inside =
        ShellWords.quotedInside(text, new ShellWords.Word(0, text.length()));
    // then
    assertThat(inside).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {"`'a b'`|1|4", "`\"a b\"`|1|4", "`\\\"a\\\"`|2|3"})
  @DisplayName("낱말 전체의 짝 따옴표만 내부 범위에서 벗긴다")
  void pairedQuoteExposesInside(String text, int start, int end) {
    // when
    Optional<ShellWords.Word> inside =
        ShellWords.quotedInside(text, new ShellWords.Word(0, text.length()));
    // then
    assertThat(inside).contains(new ShellWords.Word(start, end));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "` a b`|`a,b`",
        "`\ta\\tb`|`a,b`",
        "` 'a b' c`|`'a b',c`",
        "` \\\"a b\\\" c`|`\\\"a b\\\",c`",
        "` 'a b c`|`'a,b,c`",
        "` a\\\\b c`|`a\\\\b,c`",
        "` a\\\\tb c`|`a\\\\tb,c`",
        "` a\nb`|a",
        "` a\\nb`|a"
      })
  @DisplayName("따옴표 안의 공백만 낱말에 포함한다")
  void keepsWordBoundaries(String text, String expected) {
    // when
    ShellWords.Command command = ShellWords.after(text, 0);
    List<String> words =
        command.words().stream().map(w -> text.substring(w.start(), w.end())).toList();
    // then
    assertThat(words).containsExactly(expected.split(","));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "` a ; next`|3",
        "` a & next`|3",
        "` a\nnext`|2",
        "` a\\nnext`|2",
        "` a`|2",
        "` a `|3"
      })
  @DisplayName("명령의 끝은 구분자 앞이나 문자열 끝이다")
  void reportsCommandEnd(String text, int expected) {
    // when
    ShellWords.Command command = ShellWords.after(text, 0);
    // then
    assertThat(command.end()).isEqualTo(expected);
  }
}
