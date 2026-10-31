package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** 산문·셸 인자에 든 JSON 조각의 `"키": "값"`. 맨 따옴표와 역슬래시로 이스케이프한 따옴표 둘 다 읽는다. */
@DisplayName("따옴표 키 규칙은 JSON 조각의 비밀 키 값만 가리고 키·따옴표는 남긴다")
class QuotedKeyRuleTest {
  private final SecretRule rule = new QuotedKeyRule();

  /** 이 규칙이 가리지 않아야 하는 닮은꼴. 표준 묶음 전체로도 같은 표를 본다({@link RedactionTest}). */
  static Stream<String> lookalikes() {
    return Stream.of(
        "say \"password\":",
        "echo \"password\" is hidden",
        "say \"token\" and \"secret\" aloud",
        "cfg {\"password\": \"\"}",
        "cfg {\"passed\": \"abc\"}",
        "cfg {\"pass_rate\":\"0.95\"}",
        "cfg {\"bypass\": \"a b\"}",
        "cfg {\"pwd\": \"/srv/project\"}");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("비밀 키의 따옴표 값을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "{'password': 'SECRET'} → {'password': '[REDACTED]'}",
        "{'api_key': 'S ECRET', 'x': 1} → {'api_key': '[REDACTED]', 'x': 1}",
        "'password' => 'SECRET' → 'password' => '[REDACTED]'",
        "'password' := 'SECRET' → 'password' := '[REDACTED]'",
        "{'password': \"SECRET\"} → {'password': \"[REDACTED]\"}",
        "{\"password\": 'SECRET'} → {\"password\": '[REDACTED]'}",
        "{'password': 'it\\'s SECRET'} → {'password': '[REDACTED]'}",
        "cfg {\"password\": \"hunter2\"} → cfg {\"password\": \"[REDACTED]\"}",
        "cfg {\"token\": \"hunter2\"} → cfg {\"token\": \"[REDACTED]\"}",
        "cfg {\"pwd\": \"hunter2pw\"} → cfg {\"pwd\": \"[REDACTED]\"}",
        "cfg {\"pass\":\"hunter2pw\"} → cfg {\"pass\":\"[REDACTED]\"}",
        "cfg {\"apiKey\": \"S 2 pw\"} → cfg {\"apiKey\": \"[REDACTED]\"}",
        "cfg {\"api_key\":\"abc\\\"def SECRETX32\"} → cfg {\"api_key\":\"[REDACTED]\"}",
        "echo \"{\\\"token\\\":\\\"hunter2\\\"}\" → echo \"{\\\"token\\\":\\\"[REDACTED]\\\"}\"",
        "x \\\"password\\\": \\\"abc\\u0041defghi\\\" → x \\\"password\\\": \\\"[REDACTED]\\\"",
        "{\\\"api_key\\\":\\\"a\\\\\\\"b c\\\"} → {\\\"api_key\\\":\\\"[REDACTED]\\\"}"
      })
  void masksValue(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("값 없는 키 낱말·빈 값·비슷한 키·경로 pwd는 그대로 둔다")
  @MethodSource("lookalikes")
  void leavesLookalikes(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }
}
