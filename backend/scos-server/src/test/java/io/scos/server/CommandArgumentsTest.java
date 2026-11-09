package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
  @DisplayName("bootstrap의 공백 옵션은 이메일과 파일 값을 보존한다")
  void bootstrapAcceptsSeparatedOptions() {
    var parsed =
        args("admin", "bootstrap", "--email", "admin@example.com", "--token-file", "token");
    // when
    parsed.requireBootstrapOptions();
    // then
    assertThat(parsed.required("email")).isEqualTo("admin@example.com");
    assertThat(parsed.required("token-file")).isEqualTo("token");
  }

  @Test
  @DisplayName("bootstrap의 등호 옵션은 마지막 파일 값도 보존한다")
  void bootstrapAcceptsEqualsOptions() {
    var parsed = args("admin", "bootstrap", "--email=admin@example.com", "--token-file=token");
    // when
    parsed.requireBootstrapOptions();
    // then
    assertThat(parsed.required("email")).isEqualTo("admin@example.com");
    assertThat(parsed.required("token-file")).isEqualTo("token");
  }

  @ParameterizedTest
  @ValueSource(strings = {"--email", "--token-file"})
  @DisplayName("bootstrap 마지막 옵션에 값이 없으면 거부한다")
  void bootstrapRejectsTrailingEmptyOption(String option) {
    // when·then
    assertThatThrownBy(() -> args("admin", "bootstrap", option).requireBootstrapOptions())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bootstrap");
  }

  @Test
  @DisplayName("bootstrap은 다음 옵션을 이메일 값으로 삼지 않는다")
  void bootstrapDoesNotConsumeNextOption() {
    // when·then
    assertThatThrownBy(
            () ->
                args("admin", "bootstrap", "--email", "--token-file=token")
                    .requireBootstrapOptions())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bootstrap");
  }

  @ParameterizedTest
  @CsvSource({"source,bootstrap", "admin,status"})
  @DisplayName("bootstrap 이외의 명령은 공백 옵션을 새로 해석하지 않는다")
  void unrelatedCommandsKeepOptionGrammar(String command, String subcommand) {
    var parsed = args(command, subcommand, "--email", "admin@example.com");
    // when·then
    assertThatThrownBy(() -> parsed.option("email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--email needs a value");
  }

  @Test
  @DisplayName("텍스트 옵션을 주지 않으면 값이 없다")
  void followingOptionMayBeAbsent() {
    var value = args("source", "access", "explain", "doc").followingOption("user");
    // then
    assertThat(value).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"--user", "--user="})
  @DisplayName("값 없는 텍스트 옵션은 거부한다")
  void followingOptionRequiresValue(String option) {
    // when·then
    assertThatThrownBy(
            () -> args("source", "access", "explain", "doc", option).followingOption("user"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--user needs a value");
  }

  @Test
  @DisplayName("다음 옵션을 텍스트 값으로 오인하지 않는다")
  void followingOptionDoesNotConsumeAnotherOption() {
    // when·then
    assertThatThrownBy(() -> args("--user", "--limit=2").followingOption("user"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("공백 텍스트 옵션은 앞선 다른 인자를 건너뛴다")
  void followingOptionFindsSeparatedValue() {
    var value =
        args("source", "access", "explain", "doc", "--user", "reader").followingOption("user");
    // then
    assertThat(value).contains("reader");
  }

  @Test
  @DisplayName("등호 텍스트 옵션도 같은 값으로 읽는다")
  void followingOptionAcceptsEquals() {
    var value = args("--user=reader").followingOption("user");
    // then
    assertThat(value).contains("reader");
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
