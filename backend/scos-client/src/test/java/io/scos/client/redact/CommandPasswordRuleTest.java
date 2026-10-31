package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 특정 명령의 비밀번호 인자: `redis-cli -a`, `az login -p`, `docker login -p`, mysql 계열의 붙여 쓴 `-p<비밀번호>`. 낱말은
 * 셸처럼 나눈다 — 따옴표 안의 공백은 낱말 안이고, 뒤가 공백인 `|` `;` `&`와 줄바꿈에서 명령이 끝난다.
 */
@DisplayName("명령 비밀번호 규칙은 그 명령의 비밀번호 낱말만 가리고 포트·다른 도구의 -p는 둔다")
class CommandPasswordRuleTest {
  private final SecretRule rule = new CommandPasswordRule();

  @Test
  @DisplayName("닫히지 않은 홑따옴표 비밀번호는 다음 줄까지 가린다")
  void masksUnterminatedPasswordAcrossLineBreak() {
    // when
    String out = rule.apply("mysql -p'a b\nc");
    // then
    assertThat(out).isEqualTo("mysql -p'[REDACTED]");
  }

  @Test
  @DisplayName("짝 따옴표 안의 마지막 공백도 비밀번호에 포함한다")
  void masksTrailingBlankInsideQuotedPassword() {
    // when
    String out = rule.apply("mysql -p'a b ' db");
    // then
    assertThat(out).isEqualTo("mysql -p'[REDACTED]' db");
  }

  /** 이 규칙이 가리지 않아야 하는 닮은꼴. 표준 묶음 전체로도 같은 표를 본다({@link RedactionTest}). */
  static Stream<String> lookalikes() {
    return Stream.of(
        "mysql -p -u root mydb",
        "mysql -e \"select 1\" -p",
        "mysql -p; ls",
        "mysql -h host --protocol=tcp",
        "mysql -p3306:3306",
        "docker run --name mysql -p3306:3306 img",
        "docker run -d -p 3306:3306 mysql:8",
        "redis-cli -h \"h\" -p 6379 ping",
        "docker login -u \"user\" --password-stdin",
        "az vm open-port -g \"rg\" -p 22",
        "ssh -o 'A=b' -p 2222 host",
        "nc -p 8080 host",
        "mkdir -p dir/sub",
        "cp -pr src dst");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("명령의 비밀번호 낱말을 가리고 옵션 이름은 남긴다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        // redis-cli -a
        "redis-cli -h h -a hunter2 ping → redis-cli -h h -a [REDACTED] ping",
        "redis-cli -h \"my host\" -p 6380 -a SECRETX1 ping → redis-cli -h \"my host\" -p 6380 -a [REDACTED] ping",
        "redis-cli -a \"S 2 pw\" ping → redis-cli -a \"[REDACTED]\" ping",
        "redis-cli -a \"abc\\\"def SECRETX45\" ping → redis-cli -a \"[REDACTED]\" ping",
        "redis-cli -a \"SECRETX42\\\\\" ping → redis-cli -a \"[REDACTED]\" ping",
        "redis-cli -a x -a SECRETX72 ping → redis-cli -a [REDACTED] -a [REDACTED] ping",
        "redis-cli\t-a\tSECRETX13 → redis-cli\t-a\t[REDACTED]",
        "redis-cli -a pa&ss1234 ping → redis-cli -a [REDACTED] ping",
        "`redis-cli -a SECA9\nmysql -u root -pSECB9` → `redis-cli -a [REDACTED]\nmysql -u root -p[REDACTED]`",
        // docker login -p
        "docker login -u me -p hunter2 reg → docker login -u me -p [REDACTED] reg",
        "docker login registry.io -u x -p hunter2pw → docker login registry.io -u x -p [REDACTED]",
        "docker login -u \"John Smith\" -p hunter2pw → docker login -u \"John Smith\" -p [REDACTED]",
        "docker login -u x -p \"hunter 2 pw\" → docker login -u x -p \"[REDACTED]\"",
        "docker\tlogin\t-u\ta\t-p\tSECRETX12 → docker\tlogin\t-u\ta\t-p\t[REDACTED]",
        "docker login -u me -p pa|ss1234 → docker login -u me -p [REDACTED]",
        "docker login -p a -p SECRETX74 reg → docker login -p [REDACTED] -p [REDACTED] reg",
        // az login -p
        "az login -u x -p hunter2pw → az login -u x -p [REDACTED]",
        "az login --service-principal -u x -p hunter2pw --tenant t → az login --service-principal -u x -p [REDACTED] --tenant t",
        "az login -u \"a b\" -p 'S 2 pw' → az login -u \"a b\" -p '[REDACTED]'",
        // mysql 계열 -p<비밀번호>
        "mysql -uroot -phunter2 db → mysql -uroot -p[REDACTED] db",
        "mysqldump -pS3cret db → mysqldump -p[REDACTED] db",
        "docker exec db mysql -h 127.0.0.1 -u root -pS3cret -e 'select 1' → docker exec db mysql -h 127.0.0.1 -u root -p[REDACTED] -e 'select 1'",
        "docker exec -it mysql mysql -uroot -phunter2secret → docker exec -it mysql mysql -uroot -p[REDACTED]",
        "sudo -E mysql -uroot -phunter2secret → sudo -E mysql -uroot -p[REDACTED]",
        "kubectl exec -it pod -- mysql -uroot -phunter2secret → kubectl exec -it pod -- mysql -uroot -p[REDACTED]",
        "ssh h -t mysql -uroot -phunter2secret → ssh h -t mysql -uroot -p[REDACTED]",
        "time -p mysql -uroot -phunter2secret → time -p mysql -uroot -p[REDACTED]",
        "mysql -u root -p\\\"S3cret\\\" mydb → mysql -u root -p\\\"[REDACTED]\\\" mydb",
        "mysql -u root -p\"aaa bbb SECRETX42 ccc\" db → mysql -u root -p\"[REDACTED]\" db",
        "mysql -u root -p'aaa bbb SECRETX43 ccc' db → mysql -u root -p'[REDACTED]' db",
        "mysqldump -h \"a b\" -u r -p\"x \\\"y\\\" SECRETX44\" db → mysqldump -h \"a b\" -u r -p\"[REDACTED]\" db",
        "mysql -e \"select 1\" -uroot -pabc123 → mysql -e \"select 1\" -uroot -p[REDACTED]",
        "mysql -e '' -phunter2secret → mysql -e '' -p[REDACTED]",
        "mysql -e 'it\\'s' -uroot -phunter2secret → mysql -e 'it\\'s' -uroot -p[REDACTED]",
        "mysql --ssl-ca=/etc/ssl/certs/ca.pem --ssl-cert=/etc/ssl/certs/client.pem --ssl-key=/etc/ssl/private/client.pem --connect-timeout=10 --max-allowed-packet=64M --default-character-set=utf8mb4 -h db.internal -uroot -phunter2secret → mysql --ssl-ca=/etc/ssl/certs/ca.pem --ssl-cert=/etc/ssl/certs/client.pem --ssl-key=/etc/ssl/private/client.pem --connect-timeout=10 --max-allowed-packet=64M --default-character-set=utf8mb4 -h db.internal -uroot -p[REDACTED]",
        "mysql\t-u\troot\t-pSECRETX45 → mysql\t-u\troot\t-p[REDACTED]",
        "mysql -pa\\\\bTAIL1 → mysql -p[REDACTED]",
        "mysql -pab;cd && ls → mysql -p[REDACTED] && ls",
        "mysql -p'a b'cd db → mysql -p'[REDACTED] db",
        "bash -c \"mysql -u r -p'abc def\" → bash -c \"mysql -u r -p'[REDACTED]",
        "x\\nmysql -uroot -phunter2secret → x\\nmysql -uroot -p[REDACTED]"
      })
  void masksValueKeepsName(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @Test
  @DisplayName("따옴표 인자가 짝 탐색 상한보다 길어도 뒤의 -p 비밀번호를 가린다")
  void masksMysqlPasswordAfterOverlongQuotedArgument() {
    // given
    String query = "'select " + "a, ".repeat(150) + "b from t'";

    // when
    String out = rule.apply("mysql -e " + query + " -uroot -phunter2secret");

    // then
    assertThat(out).isEqualTo("mysql -e " + query + " -uroot -p[REDACTED]");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("프롬프트 -p·포트·mysql 이름 값·다른 도구의 -p는 그대로 둔다")
  @MethodSource("lookalikes")
  void leavesLookalikes(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }
}
