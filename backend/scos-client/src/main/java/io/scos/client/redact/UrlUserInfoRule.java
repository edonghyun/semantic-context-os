package io.scos.client.redact;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL 사용자 정보의 비밀번호. 빈 사용자도 허용하고, 권한 부분의 마지막 {@code @} 앞까지 가린다. 비밀번호의 {@code @}·{@code #}도 비밀이다.
 * 경로·질의에서 만난 {@code @}는 사용자 정보가 아니다. {@code #}는 비밀번호에도 허용하므로 {@code user:SECRET@host#frag@x}는 모호한
 * 호스트·조각까지 비밀번호로 보고 마지막 {@code @}까지 보수적으로 가린다.
 */
final class UrlUserInfoRule implements SecretRule {
  private static final Pattern USER_INFO =
      Pattern.compile(
          "(?:(?<![a-z0-9+.-])|(?<=\\\\[nrt]))[a-z][a-z0-9+.-]*+://[^/\\s:@\"\\\\]*+:",
          Pattern.CASE_INSENSITIVE);

  @Override
  public String apply(String text) {
    if (!text.contains("://")) {
      return text;
    }
    Matcher user = USER_INFO.matcher(text);
    var masker = new Masker(text);
    while (user.find()) {
      masker.mask(user.end(), passwordEnd(text, user.end()));
    }
    return masker.result();
  }

  /** 다음 슬래시까지만 훑으므로 뒤 후보 스킴과 같은 긴 구간을 되풀이해 읽지 않는다. */
  private static int passwordEnd(String text, int from) {
    int i = from;
    int end = from;
    while (i < text.length()) {
      char c = text.charAt(i);
      // 기존 평문 규칙의 ASCII 공백 경계를 유지한다. 그 밖의 문자는 비밀번호일 수 있다.
      if (c == ' ' || (c >= '\t' && c <= '\r') || c == '/' || c == '?' || c == '"' || c == '\\') {
        break;
      }
      if (c == '@') {
        end = i;
      }
      i++;
    }
    return end;
  }
}
