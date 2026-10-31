package io.scos.client.redact;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * HTTP 인증 헤더 값: `Bearer <토큰>`(헤더 밖에서도), `Authorization: Basic <b64>`, `Authorization: Token <값>`.
 * 방식 이름은 남긴다. Basic·Token은 헤더 이름이 앞서야 한다 — 단독 `basic`·`token` 낱말은 산문·경로에 흔하다.
 */
final class AuthorizationHeaderRule implements SecretRule {
  /** 헤더 이름·값 둘레의 따옴표(셸이 이스케이프한 `\"` 포함, 파이썬 딕셔너리의 홑따옴표). */
  private static final String QUOTE = "(?:\\\\?+[\"'])?+";

  private static final String HEADER = "authorization" + QUOTE + "\\s*+:\\s*+" + QUOTE;

  /** Bearer 토큰 길이 하한 — `bearer of bad news` 같은 산문을 거른다. */
  private static final int BEARER_MIN = 20;

  private static final SecretRule RULE =
      new GuardedRule(
          text -> {
            String lower = text.toLowerCase(Locale.ROOT);
            return lower.contains("bearer") || lower.contains("authorization");
          },
          List.of(
              MaskedPattern.secretGroup(
                  "bearer\\s++(?<secret>[A-Za-z0-9._~+/=-]{" + BEARER_MIN + ",}+)",
                  Pattern.CASE_INSENSITIVE),
              MaskedPattern.secretGroup(
                  HEADER + "basic\\s++(?<secret>[A-Za-z0-9+/]++=*+)", Pattern.CASE_INSENSITIVE),
              MaskedPattern.secretGroup(
                  HEADER + "token\\s++(?<secret>[A-Za-z0-9._~+/=-]++)", Pattern.CASE_INSENSITIVE)));

  /** 헤더 이름이 따로 있는 값(JSON의 `"Authorization": "Basic …"`): 값이 방식 이름으로 시작하면 그 뒤를 가린다. */
  private static final Redaction HEADER_VALUE =
      new Redaction(
          List.of(
              MaskedPattern.secretGroup(
                  "\\A\\s*+basic\\s++(?<secret>[A-Za-z0-9+/]++=*+)", Pattern.CASE_INSENSITIVE),
              MaskedPattern.secretGroup(
                  "\\A\\s*+token\\s++(?<secret>[A-Za-z0-9._~+/=-]++)", Pattern.CASE_INSENSITIVE)));

  /** Authorization 헤더의 값만 주어졌을 때(이름은 JSON 필드 이름이다). */
  static String applyToHeaderValue(String value) {
    return HEADER_VALUE.apply(value);
  }

  @Override
  public String apply(String text) {
    return RULE.apply(text);
  }
}
