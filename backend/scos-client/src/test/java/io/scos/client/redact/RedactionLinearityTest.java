package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 규칙 모양 하나를 200KB 되풀이한 텍스트 — 짝 없는 따옴표, 역슬래시 연속, 값 없는 키·옵션, 끝나지 않는 토큰 접두. 선형이면 1초 안이고, 되짚기가 터지면 수십
 * 초다. 측정 층({@code perfTest})에서 돈다.
 */
@Tag("perf")
@DisplayName("평문 가림은 규칙 모양을 200KB 되풀이한 텍스트에서도 선형이다")
class RedactionLinearityTest {
  /** 200KB 한 텍스트를 가리는 상한. */
  private static final long LINEAR_BOUND_SECONDS = 5;

  private static final int PATHOLOGICAL_CHARS = 200_000;

  private final Redaction redaction = Redaction.standard();

  @ParameterizedTest(name = "{0}")
  @DisplayName("규칙 모양을 200KB 되풀이한 텍스트도 제한 시간 안에 끝난다")
  @Timeout(LINEAR_BOUND_SECONDS)
  @ValueSource(
      strings = {
        // 키=값·따옴표 값
        "password=\"",
        "password='",
        "password='a ",
        "password=\\\"a ",
        "PASSWORD=\\\\\\\"a b ",
        "password='it\\\\'s ",
        "password=')",
        "token=a",
        "token=a1(",
        "\\ntoken = ",
        "DB_PASS",
        "\\nDBPASS ",
        "KEY\\t",
        "A_PWD=/",
        "password:\n",
        "password:\n\n  \n",
        "password:\nNEXT_VAR=example\n",
        "password:\n# comment\n",
        "password:\n- next item\n",
        "password:\nsrc/app/file.md\n",
        "password:\np4ss:word\n",
        "password:\nwJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n",
        "C:\\Users\\x\\password:\nREADME\n",
        "commands/change-my-password:\nnext-file.md\n",
        "ARTICLE_DB_PASSWORD=\nNEXT_VAR=example\n",
        "password => ",
        "PASSWORD := ",
        "'password' => 'a ",
        "{'password': 'it\\'s ",
        // 따옴표 키
        "{\\\"token\\\":\\\"",
        "{\\\\\\\"api_key\\\\\\\":\\\\\\\"a b ",
        "\\\"password\\\":\\\"a\\u0041",
        // CLI 옵션·명령 비밀번호
        "--password '",
        "sshpass -p ",
        "curl -u \"a:",
        "curl -u \\\"a b:",
        "-u a:[REDACTED] ",
        "redis-cli -a \"x ",
        "redis-cli -a \\\\\\\"x ",
        "redis-cli -a x -a ",
        "docker login -u \"x\" ",
        "az login \\\"",
        "mysql \"a\\\" -p",
        "mysql -e 'a\\\\'b' ",
        "mysql \\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\ x ",
        "mysql\\\\\\\\t\\\\\\\\t",
        // 역슬래시·따옴표 연속
        "\\\\",
        "\\\"",
        "'",
        // URL·헤더·키 블록·토큰 접두·웹훅
        "a://b:",
        "postgres://u:",
        "redis://:p@ss#SECRET@host/x ",
        "POSTGRES://user:SECRET#x@db/x ",
        "a://b:http://user:SECRET@host ",
        "Authorization: 'Basic ",
        "bearer ",
        "-----BEGIN PRIVATE KEY-----",
        "eyJaaaaa.aaaaa.",
        "sk-ant-",
        "hooks.slack.com/workflows/a-b ",
        "hooks.slack.com\\/workflows\\/T0\\/A0\\/"
      })
  void staysLinear(String unit) {
    // given
    String text = unit.repeat(PATHOLOGICAL_CHARS / unit.length());

    // when
    String out = redaction.apply(text);

    // then
    assertThat(out).isNotEmpty();
  }

  @ParameterizedTest(name = "{0}자")
  @DisplayName("다음 줄의 긴 대입 이름도 제한 시간 안에 가린다")
  @Timeout(LINEAR_BOUND_SECONDS)
  @ValueSource(ints = {50_000, 200_000, 800_000})
  void masksLongNextAssignment(int size) {
    // given
    String text = "password:\n" + "A".repeat(size) + "=example";

    // when
    String out = redaction.apply(text);

    // then
    assertThat(out).isEqualTo("password:\n[REDACTED]");
  }

  @ParameterizedTest(name = "{0}자")
  @DisplayName("다음 줄의 긴 파일 이름도 제한 시간 안에 가린다")
  @Timeout(LINEAR_BOUND_SECONDS)
  @ValueSource(ints = {50_000, 200_000, 800_000})
  void masksLongNextFilename(int size) {
    // given
    String text = "password:\n" + "a.".repeat(size / 2) + "md";

    // when
    String out = redaction.apply(text);

    // then
    assertThat(out).isEqualTo("password:\n[REDACTED]");
  }

  @ParameterizedTest(name = "{0}자")
  @DisplayName("다음 줄의 긴 소문자 상대 경로도 제한 시간 안에 가린다")
  @Timeout(LINEAR_BOUND_SECONDS)
  @ValueSource(ints = {50_000, 200_000, 800_000})
  void masksLongNextRelativePath(int size) {
    // given
    String text = "password:\n" + "a/".repeat(size / 2) + "entry";

    // when
    String out = redaction.apply(text);

    // then
    assertThat(out).isEqualTo("password:\n[REDACTED]");
  }

  @ParameterizedTest(name = "{0}자")
  @DisplayName("다음 줄의 긴 경로 닮은 비밀도 제한 시간 안에 가린다")
  @Timeout(LINEAR_BOUND_SECONDS)
  @ValueSource(ints = {50_000, 200_000, 800_000})
  void masksLongNextSecretResemblingPath(int size) {
    // given
    String text = "password:\n" + "a/".repeat(size / 2) + "Z9";

    // when
    String out = redaction.apply(text);

    // then
    assertThat(out).isEqualTo("password:\n[REDACTED]");
  }

  @ParameterizedTest(name = "{0}자")
  @DisplayName("다음 줄의 긴 등호 패딩 비밀도 제한 시간 안에 가린다")
  @Timeout(LINEAR_BOUND_SECONDS)
  @ValueSource(ints = {50_000, 200_000, 800_000})
  void masksLongNextSecretWithPadding(int size) {
    // given
    String text = "password:\n" + "aB".repeat(size / 2) + "==";

    // when
    String out = redaction.apply(text);

    // then
    assertThat(out).isEqualTo("password:\n[REDACTED]");
  }

  @ParameterizedTest(name = "{0}자")
  @DisplayName("다음 줄의 긴 base64url 모양 비밀도 제한 시간 안에 가린다")
  @Timeout(LINEAR_BOUND_SECONDS)
  @ValueSource(ints = {50_000, 200_000, 800_000})
  void masksLongNextSecretWithSeparatorAndPadding(int size) {
    // given
    String text = "password:\n" + "a_".repeat(size / 2) + "=";

    // when
    String out = redaction.apply(text);

    // then
    assertThat(out).isEqualTo("password:\n[REDACTED]");
  }
}
