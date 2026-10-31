package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** 접두로 알 수 있는 토큰: 접두마다 한 줄. 낱말 한가운데이거나 몸통이 짧으면 토큰이 아니다. */
@DisplayName("접두 토큰 규칙은 토큰을 통째로 가리고 낱말 속 접두는 둔다")
class TokenPrefixRuleTest {
  private final SecretRule rule = new TokenPrefixRule();

  /** 이 규칙이 가리지 않아야 하는 닮은꼴. 표준 묶음 전체로도 같은 표를 본다({@link RedactionTest}). */
  static Stream<String> lookalikes() {
    return Stream.of(
        "task-management-system-overview.md",
        "pip install sk-learn",
        "sk_live_short",
        "task_live_abcdefghijklmnopqrstuv",
        "mask_test_abcdefghijklmnopqrstuv",
        "ya29.short",
        "GOCSPX-short",
        "xapp-doc-readme");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("접두와 몸통이 맞는 토큰을 통째로 가리고 둘레 글자는 남긴다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "jwt eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl ok → jwt [REDACTED] ok",
        "token xoxb-0000000000-SYNTHETICfake ok → token [REDACTED] ok",
        "key sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA. → key [REDACTED].",
        "my-sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA → my-[REDACTED]",
        "ghp_SyntheticExampleFake0000000000000000hunter2 → [REDACTED]",
        "github_pat_Synthetic_Example_Fake0000 → [REDACTED]",
        "npm_SyntheticExampleFake0000000000000000 → [REDACTED]",
        "glpat-SyntheticExample-fake_000 → [REDACTED]",
        "AKIAIOSFODNN7EXAMPLE → [REDACTED]",
        "AIzaSy-SyntheticExample_fake00000000000 → [REDACTED]",
        "key sk_live_SyntheticExample0000 → key [REDACTED]",
        "key rk_test_SyntheticExample0000 → key [REDACTED]",
        "app xapp-1-Synthetic-Example-fake0000 → app [REDACTED]",
        "oauth ya29.Synthetic-Example_fake0000 → oauth [REDACTED]",
        "secret GOCSPX-SyntheticExample_fake0000 → secret [REDACTED]",
        "x\\nAKIAIOSFODNN7EXAMPLE → x\\n[REDACTED]",
        "x\\npm_SyntheticExampleFake0000000000000000 → x\\[REDACTED]"
      })
  void masksToken(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("낱말 한가운데의 접두나 몸통이 짧은 낱말은 그대로 둔다")
  @MethodSource("lookalikes")
  void leavesLookalikes(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }
}
