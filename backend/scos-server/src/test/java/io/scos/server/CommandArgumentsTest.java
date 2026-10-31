package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;

@DisplayName("명령 인자는 옵션 이름을 밝혀 형식 위반을 알린다")
class CommandArgumentsTest {
  /** --interval-seconds=900의 기간. */
  static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);

  static CommandArguments args(String... args) {
    return new CommandArguments(new DefaultApplicationArguments(args));
  }

  @Test
  @DisplayName("양의 정수 초 옵션은 그 초의 기간이다")
  void positiveSeconds() {
    // when
    var seconds = args("--interval-seconds=900").positiveSeconds("interval-seconds");

    // then
    assertThat(seconds).contains(FIFTEEN_MINUTES);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"0", "-1", "abc", "1.5"})
  @DisplayName("양의 정수가 아닌 값은 그 값을 밝혀 거부한다")
  void rejectsNonPositive(String raw) {
    // when·then
    assertThatThrownBy(() -> args("--limit=" + raw).positiveInteger("limit"))
        .hasMessage("--limit must be a positive integer: " + raw);
  }

  @Test
  @DisplayName("int를 넘는 limit은 양의 정수가 아니다")
  void limitBeyondIntIsRejected() {
    // when·then
    assertThatThrownBy(() -> args("--limit=2147483648").positiveInteger("limit"))
        .hasMessage("--limit must be a positive integer: 2147483648");
  }

  @Test
  @DisplayName("음이 아닌 정수 옵션은 0을 받는다")
  void nonNegativeAcceptsZero() {
    // when
    var days = args("--grace-days=0").nonNegativeInteger("grace-days");

    // then
    assertThat(days).contains(0);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"abc", "-1", "1.5", "2147483648"})
  @DisplayName("음이 아닌 정수가 아닌 값은 그 값을 밝혀 거부한다")
  void rejectsNegative(String raw) {
    // when·then
    assertThatThrownBy(() -> args("--grace-days=" + raw).nonNegativeInteger("grace-days"))
        .hasMessage("--grace-days must be a non-negative integer: " + raw);
  }

  @Test
  @DisplayName("범위 옵션은 범위를 밝혀 거부한다")
  void intBetweenNamesRange() {
    // when·then
    assertThatThrownBy(() -> args("--p=101").intBetween("p", 1, 100))
        .hasMessage("--p must be an integer between 1 and 100: 101");
  }

  @Test
  @DisplayName("값 없이 준 옵션은 사용법과 함께 거부한다")
  void optionWithoutValue() {
    // when·then
    assertThatThrownBy(() -> args("--space").option("space"))
        .hasMessage("--space needs a value. " + Commands.USAGE);
  }

  @Test
  @DisplayName("없는 필수 옵션은 사용법과 함께 거부한다")
  void missingRequired() {
    // when·then
    assertThatThrownBy(() -> args("collect").required("source"))
        .hasMessage("--source 가 필요합니다. " + Commands.USAGE);
  }

  @Test
  @DisplayName("없는 위치 인자는 사용법이다")
  void missingPositional() {
    // when·then
    assertThatThrownBy(() -> args("source").positional(1)).hasMessage(Commands.USAGE);
  }

  @Test
  @DisplayName("명령 이름은 첫 위치 인자다")
  void commandIsFirstPositional() {
    // when·then
    assertThat(args("--space=s", "collect", "extra").command()).isEqualTo("collect");
  }

  @Test
  @DisplayName("위치 인자가 없으면 명령 이름은 빈 문자열이다")
  void noCommandIsEmpty() {
    // when·then
    assertThat(args("--space=s").command()).isEmpty();
  }

  @Test
  @DisplayName("has는 준 옵션에만 참이다")
  void hasOnlyGivenOptions() {
    // given
    CommandArguments given = args("--dry-run");

    // when·then
    assertThat(List.of(given.has("dry-run"), given.has("force"))).containsExactly(true, false);
  }

  @Test
  @DisplayName("필수 옵션은 그 값이다")
  void requiredIsValue() {
    // when·then
    assertThat(args("collect", "--source=slack-main").required("source")).isEqualTo("slack-main");
  }

  @Test
  @DisplayName("음이 아닌 정수 옵션은 준 값이다")
  void nonNegativeIsValue() {
    // when·then
    assertThat(args("--grace-days=7").nonNegativeInteger("grace-days")).contains(7);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(ints = {1, 100})
  @DisplayName("범위 옵션은 양 끝 값을 받는다")
  void intBetweenAcceptsBounds(int raw) {
    // when·then
    assertThat(args("--p=" + raw).intBetween("p", 1, 100)).contains(raw);
  }

  @Test
  @DisplayName("양의 정수 옵션은 1을 받는다")
  void positiveAcceptsOne() {
    // when·then
    assertThat(args("--limit=1").positiveInteger("limit")).contains(1);
  }

  /** 선택 옵션 표본: 와이어 값은 소문자. */
  enum Mode {
    FULL,
    QUICK;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  @Test
  @DisplayName("선택 옵션은 와이어 값이 맞는 선택지다")
  void choiceIsMatchingConstant() {
    // when·then
    assertThat(args("--mode=quick").choice("mode", Mode.values(), Mode::wire)).contains(Mode.QUICK);
  }

  @Test
  @DisplayName("선택지 밖의 값은 선택지를 밝혀 거부한다")
  void choiceRejectsUnknown() {
    // when·then
    assertThatThrownBy(() -> args("--mode=slow").choice("mode", Mode.values(), Mode::wire))
        .hasMessage("argument 'slow' is invalid. Allowed choices are full, quick.");
  }
}
