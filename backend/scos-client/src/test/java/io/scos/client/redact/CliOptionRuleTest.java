package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** 이름으로 비밀을 받는 CLI 옵션: `--password X`, `sshpass -p X`, `-u`·`--user`의 `사용자:비밀번호`. */
@DisplayName("CLI 옵션 규칙은 비밀 옵션의 값만 가리고 옵션 이름·사용자는 남긴다")
class CliOptionRuleTest {
  private final SecretRule rule = new CliOptionRule();

  /** 이 규칙이 가리지 않아야 하는 닮은꼴. 표준 묶음 전체로도 같은 표를 본다({@link RedactionTest}). */
  static Stream<String> lookalikes() {
    return Stream.of(
        "docker run -u 1000:1000 image",
        "docker run --user=0:0 image",
        "docker run -u \"1000:1000\" img",
        "docker login --password-stdin",
        "tool --token-file /tmp/token.txt",
        "tool --password --verbose",
        "sshpass -e ssh host",
        "tool --tokens 5");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("비밀 옵션의 값과 사용자:비밀번호의 비밀번호를 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        // 공백으로 값을 받는 비밀 옵션
        "cmd --password hunter2 -v → cmd --password [REDACTED] -v",
        "tool --passwd hunter2pw → tool --passwd [REDACTED]",
        "tool --token abcdEFGH1234tokenval → tool --token [REDACTED]",
        "tool --api-key abcdEFGH1234keyval → tool --api-key [REDACTED]",
        "tool --client-secret abcdEFGH1234secretval → tool --client-secret [REDACTED]",
        "tool --password\tS3cret!x → tool --password\t[REDACTED]",
        "tool --password 'hunter 2 pw' --force → tool --password '[REDACTED]' --force",
        "tool --password \"hunter 2 pw\" --force → tool --password \"[REDACTED]\" --force",
        "tool --password 'a b'cd next → tool --password '[REDACTED]'[REDACTED] next",
        "bash -c \"x --password 'abc def\" → bash -c \"x --password '[REDACTED]",
        "sshpass -p hunter2 ssh h → sshpass -p [REDACTED] ssh h",
        "sshpass -p 'hunter2pw' ssh host → sshpass -p '[REDACTED]' ssh host",
        // -u·--user의 사용자:비밀번호
        "curl -u admin:hunter2 https://h → curl -u admin:[REDACTED] https://h",
        "curl --user=user:pass → curl --user=user:[REDACTED]",
        "curl -u admin:123456 https://h → curl -u admin:[REDACTED] https://h",
        "curl -u 12345:s3cretpw https://h → curl -u 12345:[REDACTED] https://h",
        "docker run --user 1000:dockerpw image → docker run --user 1000:[REDACTED] image",
        "curl -u \"admin:s3cretpw\" https://h → curl -u \"admin:[REDACTED]\" https://h",
        "curl -u \\\"admin:s3cretpw\\\" https://h → curl -u \\\"admin:[REDACTED]\\\" https://h",
        "curl --user \"ad min:hunter2\" https://h → curl --user \"ad min:[REDACTED]\" https://h",
        "curl -u user:\"SECRETX49\" https://x → curl -u user:\"[REDACTED]\" https://x",
        "curl -u user:'SECRETX52 z' https://x → curl -u user:[REDACTED] https://x",
        "curl -u \"admin:SECRETX7\" -u \"b:SECRETX7\" https://x → curl -u \"admin:[REDACTED]\" -u \"b:[REDACTED]\" https://x"
      })
  void masksValueKeepsName(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("uid:gid 지정과 값을 받지 않는 옵션은 그대로 둔다")
  @MethodSource("lookalikes")
  void leavesLookalikes(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }
}
