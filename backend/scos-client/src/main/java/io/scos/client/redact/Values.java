package io.scos.client.redact;

import java.util.regex.Pattern;

/**
 * 규칙이 함께 쓰는 정규식 조각: 낱말 경계와 키·옵션 뒤 값의 모양. 값은 그룹 {@value MaskedPattern#SECRET}이고 둘레의 따옴표는 그룹 밖이라 남는다.
 * 모두 소유 수량자이거나 길이 상한이 있어 되짚지 않는다.
 *
 * <p>평문에는 이스케이프한 문자열이 흔하다(로그에 찍힌 JSON, 코드의 문자열 리터럴, 셸 인자의 `\"`). 그래서 이스케이프한 공백(`\n` `\t` `\r`)은
 * 공백으로, 역슬래시가 앞선 큰따옴표는 따옴표로, 역슬래시가 앞선 슬래시(`\/`)는 URL 경로 구분자로 본다({@link #PATH_SLASH}). 몇 겹 이스케이프인지는
 * 세지 않는다 — JSON 줄의 문자열 층은 {@link JsonRedaction}이 파서로 푼다.
 */
final class Values {
  /** 대입 연산자는 긴 모양부터 읽어 꼬리 글자를 비밀값으로 오해하지 않는다. */
  static final String ASSIGNMENT = "(?>=>|:=|[=:])";

  /** 이스케이프한 공백 뒤: 앞 글자가 낱말 글자여도 경계다(`\nAKIA…`). */
  private static final String AFTER_ESCAPED_SPACE = "(?<=\\\\[nrt])";

  /** URL 경로 구분자: `/`, 또는 역슬래시가 앞선 `\/`(로그·셸 인자에 찍힌 JSON 조각의 이스케이프한 슬래시). */
  static final String PATH_SLASH = "\\\\*+/";

  /** 낱말 경계: 낱말 한가운데(`task-management`의 `sk-`)가 아니다. */
  static final String BOUNDARY = "(?:(?<![A-Za-z0-9_])|" + AFTER_ESCAPED_SPACE + ")";

  /** `-`도 낱말 글자로 보는 경계(`--password`·`x-api-key` 같은 이름의 시작). */
  static final String DASH_BOUNDARY = "(?:(?<![A-Za-z0-9_-])|" + AFTER_ESCAPED_SPACE + ")";

  /** 따옴표 값 길이 상한 — 짝 없는 따옴표가 줄 끝까지 훑지 않게 한다. */
  private static final int QUOTED_MAX = 400;

  /** 큰따옴표 값: 짝인 닫는 따옴표 앞까지(공백 포함, 역슬래시가 이스케이프한 글자는 값 안이다). 닫는 따옴표가 없으면 일치하지 않는다. */
  static final String DOUBLE_QUOTED =
      "\"(?!\")(?<secret>(?:[^\"\\\\]|\\\\.?){1," + QUOTED_MAX + "}+)(?=\")";

  /**
   * 이스케이프한 큰따옴표({@code q} 그룹의 역슬래시 연속 + `"`)로 연 문자열의 안쪽 글자 하나. 닫는 따옴표는 여는 것과 역슬래시 수가 같은 따옴표다 — 수가
   * 다른 따옴표(`\"` 안의 `\\\"`)는 값 안의 따옴표다. 연속은 통째로 먹어 쪼개지 않는다.
   */
  static final String ESCAPED_CONTENT = "(?:[^\"\\\\]|\\\\++(?!\")|(?!\\k<q>\")\\\\++\")";

  /** 이스케이프한 큰따옴표를 여는 자리: 역슬래시 연속의 시작부터({@code q} 그룹). */
  static final String ESCAPED_OPEN = "(?<!\\\\)(?<q>\\\\++)\"";

  /** 이스케이프한 큰따옴표 값(`\"…\"`): 같은 수의 역슬래시가 앞선 닫는 따옴표 앞까지. */
  static final String ESCAPED_QUOTED =
      ESCAPED_OPEN
          + "(?!\\k<q>\")(?<secret>"
          + ESCAPED_CONTENT
          + "{1,"
          + QUOTED_MAX
          + "}+)(?=\\k<q>\")";

  /**
   * 코드에서 홑따옴표가 인자를 닫는 모양: `console.log('password:', pw)`의 `', `와 `')` 뒤가 공백·따옴표·구두점·끝인 경우. 이때 홑따옴표는
   * 값을 열지 않는다.
   */
  private static final String CLOSING_SINGLE_QUOTE =
      "'(?:,[ \\t]|[)}\\]](?=[\\s\"'\\\\;,)}\\]]|$))";

  /** 홑따옴표 값: 짝인 홑따옴표나 텍스트 끝 앞까지(짝 없는 홑따옴표 값도 가린다). 역슬래시가 앞선 홑따옴표(`it\\'s`)는 값 안이다. */
  static final String SINGLE_QUOTED =
      "(?!"
          + CLOSING_SINGLE_QUOTE
          + ")'(?!')(?<secret>(?:[^'\\\\]|\\\\++'?){1,"
          + QUOTED_MAX
          + "}+)(?='|$)";

  /**
   * 따옴표 없는 값의 글자: 공백·따옴표·역슬래시가 아닌 글자, 이스케이프한 공백·따옴표를 열지 않는 역슬래시, 그리고 영숫자 앞의 (이스케이프한) 큰따옴표(값 안의
   * 따옴표). 이스케이프한 공백·닫는 따옴표에서 값이 끝난다.
   */
  static final String VALUE_CHARS =
      "(?:[^\\s\"\\\\]|\\\\(?![nrt\"\\\\])|\\\\(?=\\\\)|\\\\*+\"(?=[A-Za-z0-9]))++";

  /**
   * 따옴표 없는 값(닫히지 않은 큰따옴표 뒤 포함). 인자를 닫는 홑따옴표에서는 시작하지 않고, 앞 규칙이 이미 가린 따옴표 값(`'[REDACTED]'`)을 따옴표째 다시
   * 가리지 않는다.
   */
  static final String UNQUOTED =
      "(?:\\\\*+\")?+(?!"
          + CLOSING_SINGLE_QUOTE
          + "|'?"
          + Pattern.quote(Redaction.MASK)
          + ")(?<secret>"
          + VALUE_CHARS
          + ")";

  /**
   * 앞 규칙이 가린 따옴표 값 바로 뒤에 붙은 글자(`'[REDACTED]'cd`, `"[REDACTED]"|x`). 셸에서는 따옴표 조각과 붙은 글자가 한 낱말이라 값의
   * 나머지다. 공백·끝 앞의 구두점·구분자뿐이면(`"…", next`, `'…'; ls`) 값이 아니다.
   */
  static final String GLUED_AFTER_MASKED_QUOTE =
      "(?:\\\\*+\"|')"
          + Pattern.quote(Redaction.MASK)
          + "(?:\\\\*+\"|')(?![,;)}\\]|&]*+(?:\\s|$))(?<secret>"
          + VALUE_CHARS
          + ")";

  private Values() {}
}
