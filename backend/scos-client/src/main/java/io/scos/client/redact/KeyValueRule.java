package io.scos.client.redact;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 키=값·키: 값의 값을 가린다(`password=…`, `api_key: "…"`, `DB_PASS=…`, `token=…`, `db_pass=…`). 키 이름과 연산자, 값
 * 둘레의 따옴표는 남긴다. 경로 밖의 비밀 키가 `:`로 끝나면 다음 줄도 값으로 본다(YAML `password:\n hunter2`, 메모 `DB
 * password:\nhunter2`). 다음 줄의 값 모양은 구별하지 않고 들여쓰기 없이도 가린다(`Enter password:\nNext` 포함).
 */
final class KeyValueRule implements SecretRule {
  /** 연산자 둘레 공백(같은 줄). */
  private static final String SP = "[ \\t]*+";

  /** `password`·`x-api-key`·`aws_secret_access_key`: 비밀 키 끝말로 끝나는 이름. */
  private static final String SECRET_NAME =
      Values.DASH_BOUNDARY + "[A-Za-z0-9_-]*?(?:" + SecretNames.ASSIGNED_KEY_WORDS + ")";

  /** 같은 줄 대입과, 경로 밖 키의 맨 `:` 뒤 다음 줄 값. 빈 줄은 건너뛴다. */
  private static final String SECRET_KEY =
      "(?:"
          + SECRET_NAME
          + SP
          + Values.ASSIGNMENT
          + SP
          + "|(?<![/\\\\])"
          + SECRET_NAME
          + SP
          + ":"
          + SP
          + "(?:\\r?+\\n[ \\t]*+)++)";

  /** 대문자 환경변수 이름의 끝말 중 낱말 끝에 붙어도 되는 것(`DBPASS`, `SLACKTOKEN`). */
  private static final String ENV_SUFFIXES = "SECRET|TOKEN|PASS(?:WORD|WD)?|CREDENTIALS?";

  /**
   * `MYSQL_PWD`·`mysql_pwd`처럼 접두와 `_`가 앞선 `PWD`. 맨 `PWD`(셸의 현재 디렉터리)·`OLDPWD`와 경로
   * 값(`OLD_PWD=/home/x`)은 비밀이 아니다.
   */
  private static final String PWD_NAME =
      "[A-Za-z0-9_]*_(?:PWD|pwd)(?!" + SP + Values.ASSIGNMENT + SP + "/)";

  /**
   * 환경변수 모양 이름(대소문자 구분). 끝말은 {@link #ENV_SUFFIXES}이거나 `KEY`다. `KEY`는 이름 전체이거나 `_` 뒤일
   * 때만(`MONKEY`·`HOTKEY` 제외). 이름 전체가 `PASS`인 `PASS:`는 테스트 러너 출력(`--- PASS: TestFoo`)이라 제외한다.
   */
  private static final String ENV_KEY =
      Values.BOUNDARY
          + "(?!PASS"
          + SP
          + ":)(?:(?:[A-Za-z0-9_]*_)?(?:KEY|"
          + ENV_SUFFIXES
          + ")|[A-Z0-9_]*(?:"
          + ENV_SUFFIXES
          + ")|"
          + PWD_NAME
          + ")"
          + SP
          + Values.ASSIGNMENT
          + "(?!=)"
          + SP;

  /** 접두 없는 맨 `token` 키. 비교(`==`)는 대입이 아니다. */
  private static final String BARE_TOKEN =
      Values.DASH_BOUNDARY + "token" + SP + Values.ASSIGNMENT + "(?!=)" + SP;

  /**
   * 평문 `pass=`(`db_pass=`·`--pass=` 포함, `bypass`·`compass` 제외). `=>`·`:=`도 대입이며, 맨 `:`는 제외한다(`pass:
   * ok`·`PASS:` 테스트 출력은 둔다).
   */
  private static final String PASS_ASSIGN =
      Values.DASH_BOUNDARY + "(?:[A-Za-z0-9_-]*[_-])?pass" + SP + "(?>=>|:=|=)(?!=)" + SP;

  /** 값이 코드 키워드면 비밀이 아니다. */
  private static final String NOT_KEYWORD =
      "(?!(?:null|true|false|none|undefined|new|nil|this|self)(?![A-Za-z0-9_]))";

  /**
   * 값이 코드로 보이면(`compute(x)`, `next[0]`, `list<int>`, `other)`, `a.b`) 비밀이 아니다. `.`는 식별자가 2자 이상일 때만
   * 코드로 본다(`s.AbC…` 같은 한 글자 접두 비밀을 지키려고).
   */
  private static final String NOT_CODE = "(?![A-Za-z_$]{1,30}+[(\\[<)])(?![A-Za-z_$]{2,30}+\\.)";

  /**
   * 맨 `token`의 따옴표 없는 값은 코드처럼 보이지 않을 때만 비밀이다: {@link #NOT_CODE}에 `;`(문장 끝)도 코드로 보고, 8자 이상이거나 숫자를
   * 포함해야 한다(`token: string`은 남는다). 앞 200자 안에서만 숫자를 찾아 키가 반복돼도 선형이다.
   */
  private static final String BARE_TOKEN_GUARD =
      "(?![\"'\\\\])"
          + NOT_KEYWORD
          + "(?![A-Za-z_$]{1,30}+[(;)\\[<])(?![A-Za-z_$]{2,30}+\\.)"
          + "(?:(?=[^\\s\"\\\\]{8})|(?=[^\\s\"\\\\]{0,200}\\d))";

  private static final SecretRule RULE =
      new GuardedRule(text -> text.indexOf('=') >= 0 || text.indexOf(':') >= 0, rules());

  private static List<SecretRule> rules() {
    var all = new ArrayList<SecretRule>();
    // 따옴표 값(공백 포함)을 먼저 가린다 — 따옴표 없는 값 규칙은 따옴표 값의 첫 낱말만 가린다.
    quoted(all, SECRET_KEY, Pattern.CASE_INSENSITIVE);
    quoted(all, ENV_KEY, 0);
    quoted(all, BARE_TOKEN, Pattern.CASE_INSENSITIVE);
    quoted(all, PASS_ASSIGN, Pattern.CASE_INSENSITIVE);
    all.add(MaskedPattern.secretGroup(SECRET_KEY + Values.UNQUOTED, Pattern.CASE_INSENSITIVE));
    all.add(
        MaskedPattern.secretGroup(
            PASS_ASSIGN + "(?:\\\\*+\")?+" + NOT_KEYWORD + NOT_CODE + Values.UNQUOTED,
            Pattern.CASE_INSENSITIVE));
    all.add(
        MaskedPattern.secretGroup(
            BARE_TOKEN + BARE_TOKEN_GUARD + Values.UNQUOTED, Pattern.CASE_INSENSITIVE));
    // 따옴표로 연 맨 token 값은 닫히지 않아도 비밀이다(코드에서 문자열을 대입하는 모양).
    all.add(
        MaskedPattern.secretGroup(
            BARE_TOKEN + "(?:\\\\*+\"|'(?!'))(?<secret>" + Values.VALUE_CHARS + ")",
            Pattern.CASE_INSENSITIVE));
    all.add(MaskedPattern.secretGroup(ENV_KEY + Values.UNQUOTED, 0));
    return List.copyOf(all);
  }

  private static void quoted(List<SecretRule> into, String key, int flags) {
    into.add(MaskedPattern.secretGroup(key + Values.DOUBLE_QUOTED, flags));
    into.add(MaskedPattern.secretGroup(key + Values.ESCAPED_QUOTED, flags));
    into.add(MaskedPattern.secretGroup(key + Values.SINGLE_QUOTED, flags));
    into.add(MaskedPattern.secretGroup(key + Values.GLUED_AFTER_MASKED_QUOTE, flags));
  }

  @Override
  public String apply(String text) {
    return RULE.apply(text);
  }
}
