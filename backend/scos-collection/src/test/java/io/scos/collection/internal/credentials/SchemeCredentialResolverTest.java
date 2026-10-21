package io.scos.collection.internal.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** credentialRef 스킴 디스패치(TS SchemeCredentialResolver, ADR-0022 §2). */
@Tag("process")
@DisplayName("credentialRef는 스킴(env·file·command)으로 해석기를 고른다(ADR-0022 §2)")
class SchemeCredentialResolverTest {
  static final String NOTION_TOKEN = "notion-token-value";
  static final String JIRA_TOKEN = "jira-token-value";

  /** CRLF .env에서 온 값 — 헤더에 실으면 JDK가 값 전체를 담은 예외를 낸다(헤더 지점이 거부한다: CredentialHeaderValueTest). */
  static final String CRLF_VALUE = "xoxb-crlf-secret\r";

  /** 여러 줄 JSON 자격 증명(예: 서비스 계정 키) — 헤더가 아니라 본문·서명에 쓰이므로 줄바꿈이 정상이다. */
  static final String MULTILINE_JSON = "{\n  \"type\": \"service_account\"\n}";

  final SchemeCredentialResolver resolver =
      SchemeCredentialResolver.standard(
          Map.of(
              "NOTION_TOKEN", NOTION_TOKEN, "CRLF_TOKEN", CRLF_VALUE, "LF_TOKEN", "xoxb-lf\nX: y"));

  @Test
  @DisplayName("TS가 등록한 env:NOTION_TOKEN을 환경변수 값으로 푼다")
  void resolvesTsRegisteredEnvRef() {
    // when
    String value = resolver.resolve("env:NOTION_TOKEN");

    // then
    assertThat(value).isEqualTo(NOTION_TOKEN);
  }

  @Test
  @DisplayName("file: ref는 그 파일의 내용으로 푼다")
  void resolvesFileRef(@TempDir Path dir) throws IOException {
    // given
    Path token = Files.writeString(dir.resolve("jira-token"), JIRA_TOKEN);

    // when
    String value = resolver.resolve("file:" + token);

    // then
    assertThat(value).isEqualTo(JIRA_TOKEN);
  }

  @Test
  @DisplayName("command: ref는 그 명령의 출력으로 푼다")
  void resolvesCommandRef() {
    // when
    String value = resolver.resolve("command:printf " + JIRA_TOKEN);

    // then
    assertThat(value).isEqualTo(JIRA_TOKEN);
  }

  @Test
  @DisplayName("같은 스킴의 해석기가 둘이면 조립 시점에 거부한다")
  void rejectsDuplicateScheme() {
    // given
    List<CredentialScheme> twice =
        List.of(new EnvironmentCredentials(Map.of()), new EnvironmentCredentials(Map.of()));

    // when·then
    assertThatThrownBy(() -> SchemeCredentialResolver.of(twice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("env");
  }

  @Nested
  @DisplayName("ref를 풀 수 없으면")
  class Unresolvable {
    @Test
    @DisplayName("모르는 스킴은 지원 스킴 목록을 담아 거부한다")
    void rejectsUnknownScheme() {
      // when·then
      assertThatThrownBy(() -> resolver.resolve("vault:slack"))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("vault")
          .hasMessageContaining("env, file, command");
    }

    @Test
    @DisplayName("스킴이 없는 ref(옛 Java 형식)는 ref를 오류에 싣지 않고 거부한다")
    void rejectsRefWithoutSchemeWithoutEchoingIt() {
      // given
      String pastedToken = "xoxb-pasted-instead-of-ref";

      // when·then
      assertThatThrownBy(() -> resolver.resolve(pastedToken))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("env:")
          .hasMessageNotContaining(pastedToken);
    }

    @Test
    @DisplayName("스킴 자리에 이름이 아닌 것(이메일)이 오면 그 부분을 오류에 싣지 않는다")
    void rejectsNonSchemePrefixWithoutEchoingIt() {
      // given
      String pastedBasic = "user@example.test:ATATT-secret";

      // when·then
      assertThatThrownBy(() -> resolver.resolve(pastedBasic))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("no scheme prefix")
          .hasMessageNotContaining("user@example.test");
    }

    @Test
    @DisplayName("자격 증명 실패는 invalid_configuration으로 분류한다")
    void failuresAreInvalidConfiguration() {
      // when
      CallFailureException failure =
          catchThrowableOfType(
              CallFailureException.class, () -> resolver.resolve("env:MISSING_TOKEN"));

      // then
      assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.INVALID_CONFIGURATION);
    }
  }

  @Nested
  @DisplayName("값에 줄바꿈이 있으면")
  class ValueWithLineBreaks {
    /** B-M1(CRLF .env 값)을 해석 지점에서 헤더 지점으로 옮긴 계약: 해석은 값을 그대로 돌려주고(TS env:와 같음), 헤더에 실으려 하면 거부한다. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"env:CRLF_TOKEN, xoxb-crlf", "env:LF_TOKEN, xoxb-lf"})
    @DisplayName("env: 값은 헤더 지점에서 값 없이 거부한다")
    void rejectsLineBreakValueAtHeader(String ref, String secretPart) {
      // given: 비밀 조각(secretPart)은 손으로 쓴다 — 해석 결과에서 계산하면 줄바꿈이 남아 값 전체가 샐 때만 걸린다
      String value = resolver.resolve(ref);

      // when
      CallFailureException failure =
          catchThrowableOfType(
              CallFailureException.class, () -> CredentialResolver.headerValue(value));

      // then
      assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.INVALID_CONFIGURATION);
      assertThat(failure).hasMessageNotContaining(secretPart);
    }

    @Test
    @DisplayName("file: 값의 줄바꿈은 그대로 돌려준다(여러 줄 JSON 자격 증명)")
    void keepsLineBreaksInFileValue(@TempDir Path dir) throws IOException {
      // given
      Path file = dir.resolve("service-account.json");
      Files.writeString(file, MULTILINE_JSON);

      // when
      String value = resolver.resolve("file:" + file);

      // then
      assertThat(value).isEqualTo(MULTILINE_JSON);
    }

    @Test
    @DisplayName("command: 출력의 줄바꿈은 그대로 돌려준다(여러 줄 JSON 자격 증명)")
    void keepsLineBreaksInCommandOutput() {
      // when
      String value = resolver.resolve("command:printf '{\\n  \"type\": \"service_account\"\\n}'");

      // then
      assertThat(value).isEqualTo(MULTILINE_JSON);
    }
  }
}
