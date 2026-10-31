package io.scos.client.redact;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 평문에 든 JSON 조각의 비밀 키 값(`cfg {"password": "…"}`, 셸 인자의 `{\"token\":\"…\"}`). 줄 전체가 JSON이면 {@link
 * JsonRedaction}이 필드 이름으로 가리고, 이 규칙은 JSON으로 읽히지 않는 텍스트 안의 조각을 맡는다. 키와 따옴표는 남긴다.
 */
final class QuotedKeyRule implements SecretRule {
  /** 맨 따옴표 조각의 값: 닫는 따옴표까지, 이스케이프한 글자는 값 안이다. */
  private static final String BARE_VALUE = "(?<secret>(?:[^\"\\\\]|\\\\.)++)";

  /** 셸 등이 따옴표를 역슬래시로 이스케이프한 조각의 값: 키를 연 따옴표와 역슬래시 수가 같은 닫는 따옴표 앞까지. */
  private static final String ESCAPED_VALUE = "(?<secret>" + Values.ESCAPED_CONTENT + "++)";

  private static final String OPERATOR = "\\s*+" + Values.ASSIGNMENT + "\\s*+";

  private static final SecretRule RULE =
      new GuardedRule(
          text -> text.indexOf('"') >= 0 || text.indexOf('\'') >= 0,
          List.of(
              bare(SecretNames.FIELD + "|" + SecretNames.PASS_FIELD, ""),
              bare(SecretNames.PWD_FIELD, "(?!/)"),
              singleKey(SecretNames.FIELD + "|" + SecretNames.PASS_FIELD, ""),
              singleKey(SecretNames.PWD_FIELD, "(?!/)"),
              singleValue(SecretNames.FIELD + "|" + SecretNames.PASS_FIELD, ""),
              singleValue(SecretNames.PWD_FIELD, "(?!/)"),
              escaped(SecretNames.FIELD + "|" + SecretNames.PASS_FIELD, ""),
              escaped(SecretNames.PWD_FIELD, "(?!/)")));

  private static SecretRule bare(String key, String valueGuard) {
    return MaskedPattern.secretGroup(
        "\"(?:" + key + ")\"" + OPERATOR + "\"" + valueGuard + BARE_VALUE,
        Pattern.CASE_INSENSITIVE);
  }

  private static SecretRule singleKey(String key, String valueGuard) {
    return MaskedPattern.secretGroup(
        "'(?:" + key + ")'" + OPERATOR + "\"" + valueGuard + BARE_VALUE, Pattern.CASE_INSENSITIVE);
  }

  private static SecretRule singleValue(String key, String valueGuard) {
    return MaskedPattern.secretGroup(
        "[\"'](?:"
            + key
            + ")[\"']"
            + OPERATOR
            + "'"
            + valueGuard
            + "(?<secret>(?:[^'\\\\]|\\\\.)++)",
        Pattern.CASE_INSENSITIVE);
  }

  private static SecretRule escaped(String key, String valueGuard) {
    return MaskedPattern.secretGroup(
        Values.ESCAPED_OPEN
            + "(?:"
            + key
            + ")\\k<q>\""
            + OPERATOR
            + "\\k<q>\""
            + valueGuard
            + ESCAPED_VALUE,
        Pattern.CASE_INSENSITIVE);
  }

  @Override
  public String apply(String text) {
    return RULE.apply(text);
  }
}
