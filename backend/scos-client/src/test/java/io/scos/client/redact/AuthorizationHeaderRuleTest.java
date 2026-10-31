package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** HTTP 인증 헤더: Bearer는 어디서든, Basic·Token은 Authorization 헤더 이름 뒤에서만. 방식 이름은 남긴다. */
@DisplayName("인증 헤더 규칙은 자격 증명만 가리고 헤더 이름·방식 이름은 남긴다")
class AuthorizationHeaderRuleTest {
  private final SecretRule rule = new AuthorizationHeaderRule();

  /** 이 규칙이 가리지 않아야 하는 닮은꼴. 표준 묶음 전체로도 같은 표를 본다({@link RedactionTest}). */
  static Stream<String> lookalikes() {
    return Stream.of(
        "the bearer of bad news",
        "basic usage of the api",
        "src/main/java/basic/AbstractTokenBasicService/Impl.java",
        "basic configurationOfTheProjectSetupThings",
        "Basic aHVudGVyMjpodW50ZXIyaHVudGVyMg",
        "the token authorization flow uses a token endpoint");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("Bearer·Basic·Token 뒤의 자격 증명을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345 → Authorization: Bearer [REDACTED]",
        "Authorization: Basic dXNlcjpwYXNz → Authorization: Basic [REDACTED]",
        "Authorization: Basic dXNlcjpTRUNSRVRYNTY= → Authorization: Basic [REDACTED]",
        "curl -H \"Authorization: Basic dXNlcjpwYXNz\" https://h → curl -H \"Authorization: Basic [REDACTED]\" https://h",
        "-H \\\"Authorization: Basic dXNlcjpwYXNz\\\" → -H \\\"Authorization: Basic [REDACTED]\\\"",
        "{'Authorization': 'Basic dXNlcjpwYXNz'} → {'Authorization': 'Basic [REDACTED]'}",
        "x {\\\"Authorization\\\": \\\"Basic dXNlcjpwYXNz\\\"} → x {\\\"Authorization\\\": \\\"Basic [REDACTED]\\\"}",
        "Authorization: Token 9944b09199c62bcf9418ad846dd0e4bbdfc6ee4b → Authorization: Token [REDACTED]",
        "headers = {'Authorization': 'Token abcd1234efgh5678'} → headers = {'Authorization': 'Token [REDACTED]'}"
      })
  void masksCredential(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("헤더 밖의 basic·token 낱말과 짧은 bearer 산문은 그대로 둔다")
  @MethodSource("lookalikes")
  void leavesLookalikes(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }
}
