package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** PEM 개인 키 블록: BEGIN부터 END까지, END가 없으면 텍스트 끝까지. */
@DisplayName("개인 키 규칙은 BEGIN부터 END까지 가리고 키가 아닌 PEM은 둔다")
class PrivateKeyBlockRuleTest {
  private final SecretRule rule = new PrivateKeyBlockRule();

  /** 이 규칙이 가리지 않아야 하는 닮은꼴. 표준 묶음 전체로도 같은 표를 본다({@link RedactionTest}). */
  static Stream<String> lookalikes() {
    return Stream.of(
        "-----END RSA PRIVATE KEY-----",
        "-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("개인 키 블록을 통째로 가리고 블록 밖 글자는 남긴다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----\nnext` → `[REDACTED]\nnext`",
        "`key: -----BEGIN PRIVATE KEY-----\nMIIEvQ\n-----END PRIVATE KEY----- done` → key: [REDACTED] done",
        "`-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk\nAAAA` → [REDACTED]"
      })
  void masksBlock(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("BEGIN 없는 END 줄과 공개 키 블록은 그대로 둔다")
  @MethodSource("lookalikes")
  void leavesLookalikes(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }
}
