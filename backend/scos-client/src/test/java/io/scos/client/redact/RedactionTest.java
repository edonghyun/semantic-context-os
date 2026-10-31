package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** 평문 가림 묶음: 규칙을 목록 순서대로 적용한다. 규칙 하나하나의 표는 규칙마다의 테스트 클래스에 있다. */
@DisplayName("평문 가림은 규칙을 순서대로 적용하고 가릴 것이 없으면 원문을 돌려준다")
class RedactionTest {
  private final Redaction standard = Redaction.standard();

  @Test
  @DisplayName("규칙을 목록 순서대로 적용한다")
  void appliesRulesInOrder() {
    // given
    var redaction = new Redaction(List.of(text -> text + "a", text -> text + "b"));

    // when
    String out = redaction.apply("x");

    // then
    assertThat(out).isEqualTo("xab");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("표준 묶음은 규칙 종류마다 비밀을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----` → [REDACTED]",
        "postgres://scos:hunter2@localhost:5432/db → postgres://scos:[REDACTED]@localhost:5432/db",
        "Authorization: Basic dXNlcjpwYXNz → Authorization: Basic [REDACTED]",
        "cfg {\"password\": \"hunter2\"} → cfg {\"password\": \"[REDACTED]\"}",
        "password=hunter2 next → password=[REDACTED] next",
        "cmd --password hunter2 -v → cmd --password [REDACTED] -v",
        "mysql -uroot -phunter2 db → mysql -uroot -p[REDACTED] db",
        "token xoxb-0000000000-SYNTHETICfake ok → token [REDACTED] ok",
        "https://hooks.slack.com/services/T0001/B0002/abcSECRET → https://hooks.slack.com/services/T0001/B0002/[REDACTED]"
      })
  void standardMasksEveryRuleKind(String text, String expected) {
    // when
    String out = standard.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("빈 비밀 값 뒤의 파일 문맥을 보존하고 별도 비밀은 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`ARTICLE_DB_PASSWORD=\nNEXT_VAR=example` → `ARTICLE_DB_PASSWORD=\nNEXT_VAR=example`",
        "`GEMINI_API_KEY=\n\n# 주석` → `GEMINI_API_KEY=\n\n# 주석`",
        "`DB_USER=\nDB_PASSWORD=\n` → `DB_USER=\nDB_PASSWORD=\n`",
        "`password=\nhunter2` → `password=\nhunter2`",
        "`password=>\nhunter2` → `password=>\nhunter2`",
        "`password:=\nhunter2` → `password:=\nhunter2`",
        "`commands/change-my-password:\nnext-file.md` → `commands/change-my-password:\nnext-file.md`",
        "`src/app/(entry)/password:\nnext-file.md` → `src/app/(entry)/password:\nnext-file.md`",
        "`src\\app\\password:\nx` → `src\\app\\password:\nx`"
      })
  void preservesFileContextAfterEmptySecret(String text, String expected) {
    // when
    String out = standard.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("다음 줄 비밀이 경로나 대입을 닮아도 표준 묶음은 값을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`aws_secret_access_key:\n  wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY` → `aws_secret_access_key:\n  [REDACTED]`",
        "`password:\n  P\\ss` → `password:\n  [REDACTED]`",
        "`password:\n  p4ss:word` → `password:\n  [REDACTED]`",
        "`password:\n  Tr0ub4dor:` → `password:\n  [REDACTED]`",
        "`password:\n  Summer2024.Pass` → `password:\n  [REDACTED]`",
        "`password:\n  user:pass` → `password:\n  [REDACTED]`",
        "`password:\n  hunter2.abc` → `password:\n  [REDACTED]`",
        "`password:\n  HUNTER: x` → `password:\n  [REDACTED] x`",
        "`password:\nNEXT_VAR=x` → `password:\n[REDACTED]`",
        "`password:\nnext-file.md` → `password:\n[REDACTED]`",
        "`password:\n  src/app/entry` → `password:\n  [REDACTED]`",
        "`password:\n  #Abc123` → `password:\n  [REDACTED]`",
        "`password:\n- hunter2` → `password:\n[REDACTED] hunter2`",
        "`password:\nDB_PASSWORD=hunter2` → `password:\n[REDACTED]`"
      })
  void standardMasksNextSecretResemblingStructure(String text, String expected) {
    // when
    String out = standard.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("다음 줄 비밀이 등호 대입을 닮아도 표준 묶음은 값을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`client_secret:\n  dGVzdHNlY3JldA==` → `client_secret:\n  [REDACTED]`",
        "`client_secret:\n  YWJjZGVmZ2g=` → `client_secret:\n  [REDACTED]`",
        "`api-key:\n  ZXhhbXBsZQ==` → `api-key:\n  [REDACTED]`",
        "`api_key:\n  AbCdEf123456==` → `api_key:\n  [REDACTED]`",
        "`password:\n  Hunter2=x` → `password:\n  [REDACTED]`",
        "`password:\n  abc=def` → `password:\n  [REDACTED]`",
        "`password:\n  passw0rd=>` → `password:\n  [REDACTED]`"
      })
  void standardMasksNextSecretResemblingAssignment(String text, String expected) {
    // when
    String out = standard.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("다음 줄의 base64url·base32 비밀은 구분자와 패딩이 있어도 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`secret_key:\n  ZmDfcTF7_60GrrY167zsiPd67pEvs0aGOv2oasOM1Pg=` → `secret_key:\n  [REDACTED]`",
        "`client_secret:\n  q3Xv-8kLmN2pQrStUvWxYz0123456789AbCdEfGhIjk=` → `client_secret:\n  [REDACTED]`",
        "`api_key:\n  ZmDfcTF7_60GrrY167zsiPd67pEvs0aGOv2oasOM1Pg=` → `api_key:\n  [REDACTED]`",
        "`client_secret:\n  aB-cD_eF12=` → `client_secret:\n  [REDACTED]`",
        "`client_secret:\n  Zm9v_YmFy-YmF6=` → `client_secret:\n  [REDACTED]`",
        "`password:\n  sk_live_abc=` → `password:\n  [REDACTED]`",
        "`password:\n  aB-cD=>x` → `password:\n  [REDACTED]`",
        "`password:\n  aB.cD:=x` → `password:\n  [REDACTED]`",
        "`password:\n  hunter.two=x` → `password:\n  [REDACTED]`",
        "`totp_secret:\n  GEZDGNBVGY3TQOI=` → `totp_secret:\n  [REDACTED]`",
        "`password:\n  HUNTER2=` → `password:\n  [REDACTED]`"
      })
  void standardMasksNextEncodedSecret(String text, String expected) {
    // when
    String out = standard.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("규칙마다의 닮은꼴은 표준 묶음 전체로도 그대로 남는다")
  @MethodSource({
    "io.scos.client.redact.PrivateKeyBlockRuleTest#lookalikes",
    "io.scos.client.redact.UrlUserInfoRuleTest#lookalikes",
    "io.scos.client.redact.AuthorizationHeaderRuleTest#lookalikes",
    "io.scos.client.redact.QuotedKeyRuleTest#lookalikes",
    "io.scos.client.redact.KeyValueRuleTest#lookalikes",
    "io.scos.client.redact.CliOptionRuleTest#lookalikes",
    "io.scos.client.redact.CommandPasswordRuleTest#lookalikes",
    "io.scos.client.redact.TokenPrefixRuleTest#lookalikes",
    "io.scos.client.redact.SlackWebhookRuleTest#lookalikes"
  })
  void standardLeavesEveryRulesLookalikes(String text) {
    // when
    String out = standard.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }

  @Test
  @DisplayName("여러 공급자 접두가 함께 있어도 완성된 토큰 값을 가린다")
  void masksTokenAmongOtherProviderPrefixes() {
    // given
    String text =
        "eyJ xoxb-0000000000-EXAMPLEfake sk- gh github_pat_ npm_ glpat- AKIA AIza sk_ rk_ xapp- ya29. GOCSPX-";
    // when
    String out = standard.apply(text);
    // then
    assertThat(out)
        .isEqualTo(
            "eyJ [REDACTED] sk- gh github_pat_ npm_ glpat- AKIA AIza sk_ rk_ xapp- ya29. GOCSPX-");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("문자열 맨 앞의 비밀 키와 비밀번호 옵션도 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`\"password\": \"hunter2\"` → `\"password\": \"[REDACTED]\"`",
        "--password hunter2 → --password [REDACTED]"
      })
  void masksLeadingSecretMarker(String text, String expected) {
    // when
    String out = standard.apply(text);
    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("MariaDB 계열 명령의 붙여 쓴 비밀번호를 가린다")
  @CsvSource(
      delimiterString = " → ",
      value = {
        "mariadb -phunter2 db → mariadb -p[REDACTED] db",
        "mariadb-dump -phunter2 db → mariadb-dump -p[REDACTED] db"
      })
  void masksMariaDbPassword(String text, String expected) {
    // when
    String out = standard.apply(text);
    // then
    assertThat(out).isEqualTo(expected);
  }

  @Test
  @DisplayName("개인 키 블록 둘이 빈틈없이 붙어 있어도 둘 다 가린다")
  void masksAdjacentPrivateKeys() {
    // given
    String text =
        "-----BEGIN PRIVATE KEY-----first-----END PRIVATE KEY-----"
            + "-----BEGIN PRIVATE KEY-----second-----END PRIVATE KEY-----";
    // when
    String out = standard.apply(text);
    // then
    assertThat(out).isEqualTo("[REDACTED][REDACTED]");
  }

  @Test
  @DisplayName("앞 규칙이 가린 값은 뒤 규칙이 다시 가리지 않는다")
  void laterRulesLeaveEarlierMaskAlone() {
    // when
    String out = standard.apply("export GITHUB_TOKEN=ghp_SyntheticExampleFake0000000000000000");

    // then
    assertThat(out).isEqualTo("export GITHUB_TOKEN=[REDACTED]");
  }

  @Test
  @DisplayName("가릴 것이 없으면 같은 문자열 인스턴스를 돌려준다")
  void returnsSameInstanceWhenNothingToMask() {
    // given
    String text = "mysql -p -u root mydb && String token = getToken();";

    // when
    String out = standard.apply(text);

    // then
    assertThat(out).isSameAs(text);
  }
}
