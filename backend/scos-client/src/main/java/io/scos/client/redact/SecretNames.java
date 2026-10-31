package io.scos.client.redact;

import java.util.regex.Pattern;

/** 값이 비밀인 키 이름의 어휘. 평문 규칙(키=값, 따옴표 키)과 JSON 필드 이름 판정({@link JsonRedaction})이 같은 어휘를 쓴다. */
final class SecretNames {
  /** 비밀 값을 담는 키의 끝말(앞의 접두는 규칙마다 따로 허용한다). */
  static final String KEY_WORDS =
      "password|passwd|secret|api[_-]?key|access[_-]?key|account[_-]?key|access[_-]?token"
          + "|refresh[_-]?token|auth[_-]?token|secret[_-]?key|client[_-]?secret"
          + "|private[_-]?key(?:[_-]?id)?";

  /**
   * 평문 키=값의 키 끝말. 맨 `token`은 코드(`String token = getToken();`)에 흔해 여기 넣지 않고 {@link KeyValueRule}이 더
   * 엄격히 다룬다. 접두가 붙은 `api_token`·`X-Token`은 여기서 가린다.
   */
  static final String ASSIGNED_KEY_WORDS = KEY_WORDS + "|(?<=[A-Za-z0-9_-])token";

  /** JSON 키(필드 이름이나 평문에 든 `"키":`)로 나오는 비밀 키. 키:값 구조라 맨 `token`도 비밀이다. */
  static final String FIELD = "[A-Za-z0-9_-]*(?:" + KEY_WORDS + "|token)";

  /** JSON 키 `pass`(접두는 `_`·`-`로 이은 것만 — `bypass`·`passed`·`pass_rate`는 다른 키다). */
  static final String PASS_FIELD = "(?:[A-Za-z0-9_-]*[_-])?pass";

  /** JSON 키 `pwd`. 값이 `/`로 시작하면 셸의 현재 디렉터리라 비밀이 아니다. */
  static final String PWD_FIELD = "(?:[A-Za-z0-9_-]*[_-])?pwd";

  private static final Pattern SECRET_FIELD =
      Pattern.compile(FIELD + "|" + PASS_FIELD, Pattern.CASE_INSENSITIVE);
  private static final Pattern PWD = Pattern.compile(PWD_FIELD, Pattern.CASE_INSENSITIVE);
  private static final Pattern FIELD_PART = Pattern.compile("[^.\\s]++");

  private static final Pattern AUTHORIZATION =
      Pattern.compile("[A-Za-z0-9_-]*authorization", Pattern.CASE_INSENSITIVE);

  private SecretNames() {}

  /** JSON 필드 {@code name}이 Authorization 헤더인가(값은 {@link AuthorizationHeaderRule}이 본다). */
  static boolean isAuthorizationHeader(String name) {
    return AUTHORIZATION.matcher(name).matches();
  }

  /** JSON 필드 {@code name}의 문자열 값 {@code value}가 통째로 비밀인가. 빈 값은 가릴 것이 없다. */
  static boolean isSecretField(String name, String value) {
    if (value.isEmpty()) {
      return false;
    }
    var parts = FIELD_PART.matcher(name);
    while (parts.find()) {
      String part = parts.group();
      if (SECRET_FIELD.matcher(part).matches()
          || (PWD.matcher(part).matches() && !value.startsWith("/"))) {
        return true;
      }
    }
    return false;
  }
}
