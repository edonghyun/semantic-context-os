package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("perf")
@DisplayName("JSON 배열 인자의 가림은 큰 값과 많은 인자에서도 제한 시간 안에 끝난다")
class JsonArgumentsLinearityTest {
  private static final long LINEAR_BOUND_SECONDS = 5;
  private final JsonRedaction redaction = JsonRedaction.standard();

  @ParameterizedTest(name = "{0}자")
  @DisplayName("비밀번호 인자 하나가 길어져도 끝까지 가린다")
  @Timeout(LINEAR_BOUND_SECONDS)
  @ValueSource(ints = {50_000, 200_000, 800_000})
  void masksLongArgument(int size) {
    // given
    String line = "[\"--password\",\"" + "a b ".repeat(size / 4) + "\"]";

    // when
    String out = redaction.apply(line, redaction.read(line));

    // then
    assertThat(out).isEqualTo("[\"--password\",\"[REDACTED]\"]");
  }

  @ParameterizedTest(name = "{0}쌍")
  @DisplayName("옵션과 비밀번호 인자가 반복돼도 모든 원소를 가린다")
  @Timeout(LINEAR_BOUND_SECONDS)
  @ValueSource(ints = {1_000, 4_000, 16_000})
  void masksManyArguments(int count) {
    // given
    String line = "[" + "\"--password\",\"SECRET\",".repeat(count) + "\"end\"]";
    String expected = "[" + "\"--password\",\"[REDACTED]\",".repeat(count) + "\"end\"]";

    // when
    String out = redaction.apply(line, redaction.read(line));

    // then
    assertThat(out).isEqualTo(expected);
  }
}
