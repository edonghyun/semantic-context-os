package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Slack 웹훅: services는 마지막 조각만, 워크플로·트리거는 접두 뒤 조각 전부. 이스케이프한 `\/`도 경로 구분자다. */
@DisplayName("Slack 웹훅 규칙은 비밀 경로만 가리고 호스트·앞 조각은 남긴다")
class SlackWebhookRuleTest {
  private final SecretRule rule = new SlackWebhookRule();

  /** 이 규칙이 가리지 않아야 하는 닮은꼴. 표준 묶음 전체로도 같은 표를 본다({@link RedactionTest}). */
  static Stream<String> lookalikes() {
    return Stream.of(
        "see hooks.slack.com/services/ in the docs",
        "https://myhooks.slack.com/services/T0001/B0002/abcSECRET");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("웹훅 URL의 비밀 경로 조각을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "https://hooks.slack.com/services/T0001/B0002/abcSECRET → https://hooks.slack.com/services/T0001/B0002/[REDACTED]",
        "curl https://hooks.slack.com/triggers/ESYNTHETIC/0000000000000/SYNTHETICEXAMPLEFAKE00 → curl https://hooks.slack.com/triggers/[REDACTED]",
        "curl https://hooks.slack.com/workflows/TSYNTHETIC/ASYNTHETIC/0000000000000/SYNTHETICEXAMPLEFAKE00 → curl https://hooks.slack.com/workflows/[REDACTED]",
        "curl https://hooks.slack.com/workflows/abc-SECRETX75_ghi -d x → curl https://hooks.slack.com/workflows/[REDACTED] -d x",
        "hooks.slack.com/triggers/SECRETX76abc → hooks.slack.com/triggers/[REDACTED]",
        "curl https://hooks.slack.com/triggers/E01/1234/abc_def_ghi done → curl https://hooks.slack.com/triggers/[REDACTED] done",
        "https:\\/\\/hooks.slack.com\\/triggers\\/ESYNTHETIC\\/0000000000000\\/SYNTHETICEXAMPLEFAKE00 → https:\\/\\/hooks.slack.com\\/triggers\\/[REDACTED]",
        "curl -d '{\"url\":\"https:\\/\\/hooks.slack.com\\/services\\/T01\\/B02\\/abcSECRET\"}' → curl -d '{\"url\":\"https:\\/\\/hooks.slack.com\\/services\\/T01\\/B02\\/[REDACTED]\"}'"
      })
  void masksSecretPath(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("비밀 조각이 없는 문서 경로와 다른 호스트는 그대로 둔다")
  @MethodSource("lookalikes")
  void leavesLookalikes(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }
}
