package io.scos.collection.internal.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CallFailureException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** `env:<변수 이름>`(TS EnvironmentCredentialResolver). */
@DisplayName("env: 자격 증명은 환경변수 값이다(TS EnvironmentCredentialResolver)")
class EnvironmentCredentialsTest {
  static final String TOKEN = "slack-token-value";
  final EnvironmentCredentials env =
      new EnvironmentCredentials(Map.of("SLACK_TOKEN", TOKEN, "BLANK_TOKEN", "  "));

  @Test
  @DisplayName("변수 이름이 가리키는 값을 돌려준다")
  void returnsVariableValue() {
    // when
    String value = env.resolve("SLACK_TOKEN");

    // then
    assertThat(value).isEqualTo(TOKEN);
  }

  @Test
  @DisplayName("없는 변수는 변수 이름을 담아 거부한다")
  void rejectsMissingVariable() {
    // when·then
    assertThatThrownBy(() -> env.resolve("MISSING_TOKEN"))
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("MISSING_TOKEN");
  }

  @Test
  @DisplayName("공백뿐인 값은 없는 것으로 거부한다")
  void rejectsBlankValue() {
    // when·then
    assertThatThrownBy(() -> env.resolve("BLANK_TOKEN"))
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("BLANK_TOKEN");
  }
}
