package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** URL 사용자 정보의 비밀번호. 경로·질의의 콜론은 사용자 정보가 아니다. */
@DisplayName("URL 사용자 정보 규칙은 비밀번호만 가리고 스킴·사용자·호스트는 남긴다")
class UrlUserInfoRuleTest {
  private final SecretRule rule = new UrlUserInfoRule();

  /** 이 규칙이 가리지 않아야 하는 닮은꼴. 표준 묶음 전체로도 같은 표를 본다({@link RedactionTest}). */
  static Stream<String> lookalikes() {
    return Stream.of(
        "https://host:8080/path@x?a=b",
        "https://host:8080\tmail@x",
        "https://host:8080\rmail@x",
        "redis://:@localhost",
        "a://b:c",
        "https://host:");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("사용자:비밀번호@의 비밀번호만 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "redis://:SECRET@localhost:6379 → redis://:[REDACTED]@localhost:6379",
        "postgres://user:p@ssSECRET@db/x → postgres://user:[REDACTED]@db/x",
        "postgres://user:SECRET#x@db/x → postgres://user:[REDACTED]@db/x",
        "POSTGRES://user:SECRET@db/x → POSTGRES://user:[REDACTED]@db/x",
        "redis://:p@ss#SECRET@host/x?a=b → redis://:[REDACTED]@host/x?a=b",
        "postgres://u:abc\u2000SECRET@db/x → postgres://u:[REDACTED]@db/x",
        "postgres://user:foo=http://user:SECRET@host → postgres://user:foo=http://user:[REDACTED]@host",
        "a://b:http://user:SECRET@host → a://b:http://user:[REDACTED]@host",
        "postgres://scos:hunter2@localhost:5433/scos → postgres://scos:[REDACTED]@localhost:5433/scos",
        "echo\\npostgres://scos:hunter2@localhost/db → echo\\npostgres://scos:[REDACTED]@localhost/db"
      })
  void masksPassword(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @Test
  @DisplayName("조각의 @도 비밀번호일 수 있어 마지막 @까지 보수적으로 가린다")
  void masksAmbiguousFragmentAsPassword() {
    // given
    String text = "https://user:SECRET@host#frag@x";

    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo("https://user:[REDACTED]@x");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("포트·경로·질의의 콜론과 @는 그대로 둔다")
  @MethodSource("lookalikes")
  void leavesLookalikes(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }
}
