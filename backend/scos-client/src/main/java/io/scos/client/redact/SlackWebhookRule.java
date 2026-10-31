package io.scos.client.redact;

import java.util.List;

/**
 * Slack 웹훅 URL의 비밀 경로. `services/T…/B…/<비밀>`은 마지막 조각만(앞 두 조각은 워크스페이스·앱 id), 워크플로·트리거 웹훅은 어느 조각이
 * 비밀인지 가를 수 없어 접두 뒤 조각 전부(넷까지)를 가린다.
 */
final class SlackWebhookRule implements SecretRule {
  private static final String SLASH = Values.PATH_SLASH;

  private static final String HOST = Values.BOUNDARY + "hooks\\.slack\\.com" + SLASH;

  private static final String SEGMENT = "[A-Za-z0-9_-]++";

  private static final SecretRule RULE =
      new GuardedRule(
          text -> text.contains("hooks.slack.com"),
          List.of(
              MaskedPattern.secretGroup(
                  HOST
                      + "services"
                      + SLASH
                      + "T[A-Za-z0-9]++"
                      + SLASH
                      + "B[A-Za-z0-9]++"
                      + SLASH
                      + "(?<secret>[A-Za-z0-9]++)",
                  0),
              MaskedPattern.secretGroup(
                  HOST
                      + "(?:triggers|workflows)"
                      + SLASH
                      + "(?<secret>"
                      + SEGMENT
                      + "(?:"
                      + SLASH
                      + SEGMENT
                      + "){0,3}+)",
                  0)));

  @Override
  public String apply(String text) {
    return RULE.apply(text);
  }
}
