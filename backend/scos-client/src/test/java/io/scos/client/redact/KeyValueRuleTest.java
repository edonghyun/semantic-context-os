package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 키=값·키: 값. 비밀 키 이름(`password`, `api_key`, `*_token`), 환경변수 이름(`DB_PASS`, `*_KEY`, `MYSQL_PWD`), 맨
 * `token`, 평문 `pass=`. 따옴표 값은 공백째, 따옴표 없는 값은 공백·이스케이프한 공백까지.
 */
@DisplayName("키=값 규칙은 비밀 키의 값만 가리고 키·연산자·따옴표와 코드는 남긴다")
class KeyValueRuleTest {
  private final SecretRule rule = new KeyValueRule();

  /** 이 규칙이 가리지 않아야 하는 닮은꼴. 표준 묶음 전체로도 같은 표를 본다({@link RedactionTest}). */
  static Stream<String> lookalikes() {
    return Stream.of(
        // token 이름을 쓰는 코드는 그대로 둔다
        "String token = getToken();",
        "if (token == null) {",
        "if (token === other) {",
        "if (TOKEN == null) return;",
        "token: string",
        "token = null;",
        "token: Optional[str] = None",
        "this.token = getToken();",
        "obj.token = other;",
        "var token = tokenProvider.next(1);",
        "x\ntoken = getToken();",
        // pass를 쓰는 코드·테스트 출력·비슷한 이름은 그대로 둔다
        "--- PASS: TestFoo (0.00s)\nPASS: ok",
        "passed=3 bypass=true",
        "pass_rate=0.95",
        "bypass=hunter2pw",
        "compass=north",
        "getpass=hunter2pw",
        "if pass==expected {",
        "pass=true",
        "pass: ok",
        "if (pass = compute(x)) { }",
        "pass=foo()",
        "while (pass = next[0]) {}",
        "pass = list<int>",
        "int ok = (pass = other)",
        // 값이 없거나 경로이거나 이름이 KEY로 끝날 뿐인 대입은 그대로 둔다
        "password=",
        "PASSWORD=\"\" next",
        "KEY=",
        "KEY=\nNEXT=1",
        "TOKEN:",
        "SECRET_TOKEN: ",
        "HOTKEY=abc1 and MONKEY=x2",
        "PRIMARY_KEY (id) and PRIMARY KEY (a, b)",
        "PWD=/srv/project",
        "OLDPWD=/srv/x",
        "OLD_PWD=/srv/x",
        "old_pwd=/srv/x",
        "cd $PWD && ls",
        "env: PWD: /srv/x",
        // 코드 인자를 닫는 홑따옴표와 비밀 키가 없는 홑따옴표 문자열은 그대로 둔다
        "console.log('password:', pw)",
        "print('Enter your password: ')",
        "x = getpass('password: ')",
        "print('say \"hi\" please')",
        "name = 'it\\'s fine' # not a secret",
        "echo 'a \"b\" c'");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("비밀 키·환경변수·token·pass 이름 뒤의 값을 가리고 이름은 남긴다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        // 비밀 키 이름 뒤의 값을 가린다
        "password => SECRET → password => [REDACTED]",
        "= password=SECRET → = password=[REDACTED]",
        ": password: SECRET → : password: [REDACTED]",
        "PASSWORD := SECRET → PASSWORD := [REDACTED]",
        "password=>\"a b SECRET\" → password=>\"[REDACTED]\"",
        "DB_PASS := SECRET → DB_PASS := [REDACTED]",
        "pass => SECRET → pass => [REDACTED]",
        "PASS := SECRET → PASS := [REDACTED]",
        "token => secret123 → token => [REDACTED]",
        "token := getToken1() → token := [REDACTED]",
        "password=hunter2 next → password=[REDACTED] next",
        "password: \"hunter2\" ok → password: \"[REDACTED]\" ok",
        "api_key: \"a b c\" next → api_key: \"[REDACTED]\" next",
        "aws_secret_access_key = hunter2wJalrXUtnFEMI → aws_secret_access_key = [REDACTED]",
        "refresh_token=hunter2abcdef → refresh_token=[REDACTED]",
        "X-API-Token: abcdef → X-API-Token: [REDACTED]",
        "bot_token = \"abcdefgh\" → bot_token = \"[REDACTED]\"",
        "SECRET_KEY = 'hunter2' → SECRET_KEY = '[REDACTED]'",
        "export DB_PASSWORD='hunter 2 pw' && run → export DB_PASSWORD='[REDACTED]' && run",
        "DefaultEndpointsProtocol=https;AccountName=x;AccountKey=abcDEF123ghiJKL456mno==;EndpointSuffix=core.windows.net → DefaultEndpointsProtocol=https;AccountName=x;AccountKey=[REDACTED]",
        "password=:abc123 next → password=[REDACTED] next",
        "`password:\n  hunter2\nnext` → `password:\n  [REDACTED]\nnext`",
        "`password:\nZq9xW7vK2mP4` → `password:\n[REDACTED]`",
        "`DB password:\n\nZq9xW7vK2mP4 x` → `DB password:\n\n[REDACTED] x`",
        "`Enter password:\nNext` → `Enter password:\n[REDACTED]`",
        "`DB password:\nhunter2` → `DB password:\n[REDACTED]`",
        "`password:\nhunter2\nNEXT_VAR=x` → `password:\n[REDACTED]\nNEXT_VAR=x`",
        "`env:\n  SECRET_TOKEN: hunter2` → `env:\n  SECRET_TOKEN: [REDACTED]`",
        "`password = x\npassword = SECB9` → `password = [REDACTED]\npassword = [REDACTED]`",
        // 따옴표 값은 안쪽 전체를 가리고 따옴표와 뒤 글자는 남긴다
        "x PASSWORD=\"abc\\\"def SECRETX40\" y → x PASSWORD=\"[REDACTED]\" y",
        "PASSWORD=\"S 2 pw\\\\\" next → PASSWORD=\"[REDACTED]\" next",
        "PASSWORD='say \"hi\" SECRETX64' x → PASSWORD='[REDACTED]' x",
        "password='it\\'s SECRETX66' → password='[REDACTED]'",
        "password=',SECRETX9' → password='[REDACTED]'",
        "PASSWORD=')SECRETX9' → PASSWORD='[REDACTED]'",
        "password: \",xyz9\" → password: \"[REDACTED]\"",
        "password=\":abc9XyZ\" → password=\"[REDACTED]\"",
        "password: \"}abc9XyZ\" ok → password: \"[REDACTED]\" ok",
        "password=\"]abc9XyZ\" → password=\"[REDACTED]\"",
        "token=\":Sup3rS3cret\" → token=\"[REDACTED]\"",
        "pass=\"}h9Zq8w\" → pass=\"[REDACTED]\"",
        "api_key='abc\"def SECRETX65' y → api_key='[REDACTED]' y",
        "password='a \"b\" c\\'d SECRETX67' z → password='[REDACTED]' z",
        "export GH_TOKEN='x\"y SECRETX68' && run → export GH_TOKEN='[REDACTED]' && run",
        "secret = 'q\\\\\\\\ \"w\" SECRETX70' ok → secret = '[REDACTED]' ok",
        "export AUTH_TOKEN=\\\"hunter2\\\" → export AUTH_TOKEN=\\\"[REDACTED]\\\"",
        "PASSWORD=\\\"a\\\\\\\"b c\\\" next → PASSWORD=\\\"[REDACTED]\\\" next",
        "password='a b'cd next → password='[REDACTED]'[REDACTED] next",
        // 닫는 홑따옴표가 없는 값은 텍스트 끝까지 가린다
        "password='say \"hi\" SECRETX69 → password='[REDACTED]",
        "echo password='}SECRETX9 && ls → echo password='[REDACTED]",
        // 따옴표 없는 값은 역슬래시·낱말 속 따옴표를 넘고 이스케이프한 줄바꿈에서 멈춘다
        "password=ab\\cdTAIL1 → password=[REDACTED]",
        "password=abc\\u0041defghi → password=[REDACTED]",
        "password=ab\"cdTAIL1 → password=[REDACTED]",
        "password=abc12\\nnext line → password=[REDACTED]\\nnext line",
        "password=abc12\\tnext → password=[REDACTED]\\tnext",
        // 환경변수 모양 이름 뒤의 값을 가린다
        "export DB_PASS=hunter2 → export DB_PASS=[REDACTED]",
        "DBPASS=hunter2 → DBPASS=[REDACTED]",
        "myApp_TOKEN=hunter2 → myApp_TOKEN=[REDACTED]",
        "GITHUB_TOKEN: hunter2 → GITHUB_TOKEN: [REDACTED]",
        "KEY=hunter2 → KEY=[REDACTED]",
        "GH_CREDENTIALS=abc → GH_CREDENTIALS=[REDACTED]",
        "MYSQL_PWD=hunter2pw mysql -u root → MYSQL_PWD=[REDACTED] mysql -u root",
        "export mysql_pwd=\"hunter 2 pw\" → export mysql_pwd=\"[REDACTED]\"",
        "BYPASS=abc1 → BYPASS=[REDACTED]",
        "x\\nDBPASS=hunter2x → x\\nDBPASS=[REDACTED]",
        // 맨 token 뒤의 값은 코드처럼 보이지 않으면 가린다
        "token=abc123def → token=[REDACTED]",
        "token=abcdefghijklmnop → token=[REDACTED]",
        "Token=1234567 → Token=[REDACTED]",
        "Set-Cookie: token=a3f9c2e1b4d8f0a7c6e5; Path=/ → Set-Cookie: token=[REDACTED] Path=/",
        "token=SYNTHETICEXAMPLE000000000.FAKE00.SYNTHETICEXAMPLEFAKE0000000000 → token=[REDACTED]",
        "token=xK29sjd8Ahd) → token=[REDACTED]",
        "vault.token=s.AbCdEf123GhIjKl → vault.token=[REDACTED]",
        "token='abcdefghijklmnop' → token='[REDACTED]",
        "token = \"abc\" → token = \"[REDACTED]\"",
        "token=\"hunter 2 pw\" → token=\"[REDACTED]\"",
        "token: 'hunter 2 pw' → token: '[REDACTED]",
        "C:\\temp\\token=abc123456789 → C:\\temp\\token=[REDACTED]",
        // pass= 대입의 값을 가린다
        "pass=hunter2pw → pass=[REDACTED]",
        "PASS=hunter2pw → PASS=[REDACTED]",
        "pass = correcthorse → pass = [REDACTED]",
        "pass=abc; → pass=[REDACTED]",
        "db_pass=hunter2pw run → db_pass=[REDACTED] run",
        "tool --pass=hunter2pw → tool --pass=[REDACTED]",
        "pass=\"hunter 2 pw\" next → pass=\"[REDACTED]\" next"
      })
  void masksValueKeepsName(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("콜론 이외의 빈 대입은 다음 줄을 값으로 읽지 않는다")
  @ValueSource(
      strings = {
        "ARTICLE_DB_PASSWORD=\nNEXT_VAR=example",
        "GEMINI_API_KEY=\n\n# 주석",
        "DB_USER=\nDB_PASSWORD=\n",
        "password=\nhunter2",
        "password=\n  src/app/entry",
        "password=>\n  ZmDfcTF7_60GrrY167zsiPd67pEvs0aGOv2oasOM1Pg=",
        "password:=\n  user:pass",
        "password=>\nhunter2",
        "password:=\nhunter2",
        "password = \r\n\tNEXT_VAR=example",
        "api_key := \n\n\"next line\""
      })
  void leavesLineAfterEmptyAssignment(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }

  @ParameterizedTest
  @DisplayName("콜론 다음 줄은 별도 대입을 닮아도 값으로 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`password:\nNEXT_VAR=example` → `password:\n[REDACTED]`",
        "`password:\nname:` → `password:\n[REDACTED]`",
        "`password:\n  name: example` → `password:\n  [REDACTED] example`",
        "`password:\nname:\texample` → `password:\n[REDACTED]\texample`",
        "`password:\nname := example` → `password:\n[REDACTED] := example`",
        "`password:\nname => example` → `password:\n[REDACTED] => example`",
        "`password:\nname = example` → `password:\n[REDACTED] = example`",
        "`password:\nname\t=example` → `password:\n[REDACTED]\t=example`",
        "`password:\nmyApp_USER=example` → `password:\n[REDACTED]`",
        "`password:\nmyApp.user=example` → `password:\n[REDACTED]`",
        "`password:\nmyApp-user=example` → `password:\n[REDACTED]`",
        "`password:\napiVersion: v1` → `password:\n[REDACTED] v1`",
        "`password:\nname2: example` → `password:\n[REDACTED] example`",
        "`password:\nNAME2: example` → `password:\n[REDACTED] example`",
        "`password:\nmyApp2.setting: example` → `password:\n[REDACTED] example`",
        "`password:\nDB_PASSWORD=hunter2` → `password:\n[REDACTED]`",
        "`password:\napi_key:\n  hunter2` → `password:\n[REDACTED]\n  hunter2`",
        "`password:\r\n\r\n\tNEXT_VAR=` → `password:\r\n\r\n\t[REDACTED]`",
        "`password:\n  HUNTER: x` → `password:\n  [REDACTED] x`",
        "`password:\n  hunter: x` → `password:\n  [REDACTED] x`",
        "`password:\n  aB_cD: x` → `password:\n  [REDACTED] x`",
        "`password:\n  Hunter:` → `password:\n  [REDACTED]`"
      })
  void masksNextAssignment(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("다음 줄 비밀의 등호 패딩이나 붙여 쓴 등호는 대입으로 오인하지 않는다")
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
        "`password:\n  passw0rd=>` → `password:\n  [REDACTED]`",
        "`password:\n  ABCDEF==` → `password:\n  [REDACTED]`",
        "`password:\n  abcdef==` → `password:\n  [REDACTED]`",
        "`password:\n  abcdef=` → `password:\n  [REDACTED]`",
        "`password:\r\n\r\n\tYWJjZGVmZ2g=` → `password:\r\n\r\n\t[REDACTED]`"
      })
  void masksNextSecretResemblingAssignment(String text, String expected) {
    // when
    String out = rule.apply(text);

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
  void masksNextEncodedSecret(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("다음 줄의 콜론에 공백이 이어지지 않으면 비밀 값으로 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`password:\n  p4ss:word` → `password:\n  [REDACTED]`",
        "`password:\nadmin:hunter2` → `password:\n[REDACTED]`",
        "`password:\n  user:pass` → `password:\n  [REDACTED]`",
        "`password:\n  Tr0ub4dor:3` → `password:\n  [REDACTED]`"
      })
  void masksNextSecretWithColon(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("다음 줄의 대소문자와 숫자가 섞인 비밀은 맨 콜론으로 끝나도 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`password:\n  Tr0ub4dor:` → `password:\n  [REDACTED]`",
        "`password:\n  Tr0ub4dor: next` → `password:\n  [REDACTED] next`"
      })
  void masksNextMixedAlphanumericSecretEndingWithColon(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("콜론 다음 줄은 주석이나 목록을 닮아도 값으로 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`password:\n# 주석\nhunter2` → `password:\n[REDACTED] 주석\nhunter2`",
        "`password:\n\n  # 주석` → `password:\n\n  [REDACTED] 주석`",
        "`password:\n  - next item` → `password:\n  [REDACTED] next item`",
        "`password:\n* next item` → `password:\n[REDACTED] next item`",
        "`password:\n+ next item` → `password:\n[REDACTED] next item`",
        "`password:\n1. next item` → `password:\n[REDACTED] next item`",
        "`password:\n12) next item` → `password:\n[REDACTED] next item`",
        "`password:\n  #Abc123` → `password:\n  [REDACTED]`",
        "`password:\n  - hunter2` → `password:\n  [REDACTED] hunter2`",
        "`password:\n  1. hunter2` → `password:\n  [REDACTED] hunter2`"
      })
  void masksNextCommentOrList(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("경로의 일부인 키는 콜론 다음 줄을 값으로 읽지 않는다")
  @ValueSource(
      strings = {
        "commands/change-my-password:\nnext-file.md",
        "src/app/(entry)/password:\n  hunter2",
        "/password:\n\"next file\"",
        "commands/api_key:\n'hunter2'",
        "C:\\Users\\x\\password:\nREADME",
        "src\\app\\password:\nx",
        "commands\\change-my-password:\nREADME",
        "src\\app\\api_key:\n'hunter2'"
      })
  void leavesLineAfterPathKey(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }

  @ParameterizedTest
  @DisplayName("이스케이프한 공백 뒤 비밀 키도 다음 줄의 값을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`x\\npassword:\nhunter2` → `x\\npassword:\n[REDACTED]`",
        "`x\\rpassword:\nhunter2` → `x\\rpassword:\n[REDACTED]`",
        "`x\\tpassword:\nhunter2` → `x\\tpassword:\n[REDACTED]`"
      })
  void masksNextValueAfterEscapedSpaceKey(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("콜론 다음 줄은 경로나 파일명을 닮아도 값으로 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`password:\nnext-file.md` → `password:\n[REDACTED]`",
        "`password:\n  src/app/entry` → `password:\n  [REDACTED]`",
        "`password:\n  src/app/(entry)/page` → `password:\n  [REDACTED]`",
        "`password:\nsrc\\app\\entry` → `password:\n[REDACTED]`",
        "`password:\n./next-file` → `password:\n[REDACTED]`",
        "`password:\n../next-file` → `password:\n[REDACTED]`",
        "`password:\n~/next-file` → `password:\n[REDACTED]`",
        "`password:\n/tmp/next-file` → `password:\n[REDACTED]`",
        "`password:\npage.tsx` → `password:\n[REDACTED]`",
        "`password:\nC:\\temp\\next-file.md` → `password:\n[REDACTED]\\temp\\next-file.md`",
        "`password:\n  /xY9!z` → `password:\n  [REDACTED]`",
        "`password:\n  ../xY9` → `password:\n  [REDACTED]`",
        "`password:\n  ~/Tr0ub` → `password:\n  [REDACTED]`",
        "`password:\n  a:/Q9x` → `password:\n  [REDACTED]`",
        "`password:\n  hunter2/abc` → `password:\n  [REDACTED]`",
        "`password:\n  abc/def/ghi` → `password:\n  [REDACTED]`"
      })
  void masksNextPath(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("다음 줄 비밀에 경로 구분자가 섞여 있어도 값을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`aws_secret_access_key:\n  wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY` → `aws_secret_access_key:\n  [REDACTED]`",
        "`client_secret:\n  ab+cd/ef==` → `client_secret:\n  [REDACTED]`",
        "`secret_key:\n  sk_live_abc/def` → `secret_key:\n  [REDACTED]`",
        "`DB password:\nAbc/123!x` → `DB password:\n[REDACTED]`",
        "`password:\n  P\\ss` → `password:\n  [REDACTED]`",
        "`password:\n  ab+cd/ef` → `password:\n  [REDACTED]`"
      })
  void masksNextSecretWithPathSeparator(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("다음 줄 비밀에 점이 있어도 값을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`password:\n  Summer2024.Pass` → `password:\n  [REDACTED]`",
        "`api_key:\n  abc.def.Ghi` → `api_key:\n  [REDACTED]`",
        "`password:\n  hunter2.ABC` → `password:\n  [REDACTED]`",
        "`password:\n  hunter2.ab3` → `password:\n  [REDACTED]`",
        "`api_key:\n  abc.def.ghi` → `api_key:\n  [REDACTED]`",
        "`password:\n  hunter2.abc` → `password:\n  [REDACTED]`",
        "`password:\n  my.secret.ab` → `password:\n  [REDACTED]`",
        "`password:\n  q-w.e` → `password:\n  [REDACTED]`",
        "`password:\n  Hunter2-x.y` → `password:\n  [REDACTED]`",
        "`secret:\n  abc.json` → `secret:\n  [REDACTED]`"
      })
  void masksNextSecretWithDot(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest
  @DisplayName("따옴표로 명시한 다음 줄 값은 구조를 닮아도 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "`password:\n  \"NAME=secret\"` → `password:\n  \"[REDACTED]\"`",
        "`password:\n'#hunter2'` → `password:\n'[REDACTED]'`",
        "`password:\n\"- hunter2\"` → `password:\n\"[REDACTED]\"`",
        "`password:\n'src/app/entry'` → `password:\n'[REDACTED]'`",
        "`password:\r\n\r\n\t\"hunter2\"` → `password:\r\n\r\n\t\"[REDACTED]\"`"
      })
  void masksExplicitNextLineValue(String text, String expected) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("코드·테스트 출력·빈 값·경로·비슷한 이름은 그대로 둔다")
  @MethodSource("lookalikes")
  void leavesLookalikes(String text) {
    // when
    String out = rule.apply(text);

    // then
    assertThat(out).isEqualTo(text);
  }
}
