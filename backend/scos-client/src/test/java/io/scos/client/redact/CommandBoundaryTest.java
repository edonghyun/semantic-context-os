package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("명령 비밀번호는 셸 구분자와 따옴표 경계를 지킨다(E5)")
class CommandBoundaryTest {
  private final Redaction redaction = Redaction.standard();

  @ParameterizedTest(name = "{0}")
  @DisplayName("명령 구분자 뒤의 다른 명령 인자는 그대로 남긴다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "mysql -pfirst | → mysql -p[REDACTED] |",
        "mysql -pfirst|| → mysql -p[REDACTED]||",
        "mysql -pfirst&& → mysql -p[REDACTED]&&",
        "mysql -pfirst|| echo -pvisible → mysql -p[REDACTED]|| echo -pvisible",
        "mysql -pfirst&& echo -pvisible → mysql -p[REDACTED]&& echo -pvisible",
        "mysql -pfirst || → mysql -p[REDACTED] ||",
        "mysql -pfirst & → mysql -p[REDACTED] &",
        "mysql -pfirst && → mysql -p[REDACTED] &&",
        "mysql -pfirst ; → mysql -p[REDACTED] ;",
        "mysql -pfirst | echo -pvisible → mysql -p[REDACTED] | echo -pvisible",
        "mysql -pfirst || echo -pvisible → mysql -p[REDACTED] || echo -pvisible",
        "mysql -pfirst & echo -pvisible → mysql -p[REDACTED] & echo -pvisible",
        "mysql -pfirst && echo -pvisible → mysql -p[REDACTED] && echo -pvisible",
        "mysql -pfirst ; echo -pvisible → mysql -p[REDACTED] ; echo -pvisible",
        "mysql -pfirst |\\techo -pvisible → mysql -p[REDACTED] |\\techo -pvisible",
        "mysql -pfirst |\\necho -pvisible → mysql -p[REDACTED] |\\necho -pvisible",
        "mysql -pfirst |\\recho -pvisible → mysql -p[REDACTED] |\\recho -pvisible",
        "mysql -pfirst\\n echo -pvisible → mysql -p[REDACTED]\\n echo -pvisible",
        "mysql -pfirst\\r echo -pvisible → mysql -p[REDACTED]\\r echo -pvisible",
        "`mysql -pfirst\r echo -pvisible` → `mysql -p[REDACTED]\r echo -pvisible`"
      })
  void leavesFollowingCommand(String input, String expected) {
    // when
    String result = redaction.apply(input);
    // then
    assertThat(result).isEqualTo(expected);
  }

  @Test
  @DisplayName("따옴표 안의 긴 비밀번호도 공백 뒤 꼬리까지 가린다")
  void masksLongQuotedPassword() {
    // given
    String password = "x ".repeat(197) + "tail";
    // when
    String result = redaction.apply("redis-cli -a \"" + password + "\" ping");
    // then
    assertThat(result).isEqualTo("redis-cli -a \"[REDACTED]\" ping");
  }

  @Test
  @DisplayName("이스케이프한 따옴표의 긴 비밀번호도 공백 뒤 꼬리까지 가린다")
  void masksLongEscapedQuotedPassword() {
    // given
    String password = "x ".repeat(197) + "tail";
    // when
    String result = redaction.apply("redis-cli -a \\\"" + password + "\\\" ping");
    // then
    assertThat(result).isEqualTo("redis-cli -a \\\"[REDACTED]\\\" ping");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("따옴표로 감싼 빈 비밀번호는 그대로 남긴다")
  @CsvSource(
      quoteCharacter = '`',
      value = {"`redis-cli -a \"\" ping`", "`mysql -p'' db`"})
  void leavesEmptyQuotedPasswords(String text) {
    // when
    String out = redaction.apply(text);
    // then
    assertThat(out).isEqualTo(text);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("비밀번호 옵션 다음에 다른 옵션이 오면 그 옵션을 남긴다")
  @CsvSource(
      delimiterString = " → ",
      value = {
        "redis-cli -a -x ping → redis-cli -a -x ping",
        "docker login -p --password-stdin → docker login -p --password-stdin",
        "az login -p --tenant tenant-id → az login -p --tenant tenant-id"
      })
  void leavesFollowingOption(String input, String expected) {
    // when
    String result = redaction.apply(input);
    // then
    assertThat(result).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("이스케이프한 따옴표 안의 공백과 명령 구분자도 비밀번호로 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`redis-cli -a \\\"first | second\\\" ping` → `redis-cli -a \\\"[REDACTED]\\\" ping`",
        "`redis-cli -a \\\"first \\\\x\\\" ping` → `redis-cli -a \\\"[REDACTED]\\\" ping`",
        "`redis-cli -a \\\\\"first | second\\\\\" ping` → `redis-cli -a \\\\\"[REDACTED]\\\\\" ping`",
        "`redis-cli -a \\\"first \\\\\"inner\\\\\" second\\\" ping` → `redis-cli -a \\\"[REDACTED]\\\" ping`"
      })
  void masksEscapedQuotedPassword(String input, String expected) {
    // when
    String result = redaction.apply(input);
    // then
    assertThat(result).isEqualTo(expected);
  }
}
