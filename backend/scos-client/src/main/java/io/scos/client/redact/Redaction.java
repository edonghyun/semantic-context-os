package io.scos.client.redact;

import java.util.List;

/**
 * 평문의 자격 증명을 가린다: 규칙({@link SecretRule}) 목록을 차례로 적용하는 묶음이다. 메모리 파일·질의 로그 같은 평문에 바로 쓰고, JSON 줄은
 * {@link JsonRedaction}이 문자열 값마다 이것을 부른다. 원문 저장·유닛화보다 앞서 적용한다. 스레드 안전하다(규칙은 상태가 없다).
 */
public final class Redaction implements SecretRule {
  /** 가린 자리에 들어가는 표식. */
  public static final String MASK = "[REDACTED]";

  /**
   * 표준 묶음은 처음 쓸 때 만든다. 규칙 클래스의 정적 초기화가 {@link GuardedRule}을 거쳐 이 클래스를 쓰므로, 이 클래스의 정적 초기화가 규칙을 만들면 두
   * 스레드가 규칙과 이 클래스를 동시에 처음 쓸 때 서로의 초기화를 기다려 멈춘다.
   */
  private static final class Standard {
    static final Redaction INSTANCE =
        new Redaction(
            List.of(
                new PrivateKeyBlockRule(),
                new UrlUserInfoRule(),
                new AuthorizationHeaderRule(),
                new QuotedKeyRule(),
                new KeyValueRule(),
                new CliOptionRule(),
                new CommandPasswordRule(),
                new TokenPrefixRule(),
                new SlackWebhookRule()));
  }

  private final List<SecretRule> rules;

  /** 규칙은 이 순서로 적용한다 — 앞 규칙이 가린 값은 뒤 규칙에 {@link #MASK}로 보인다. */
  public Redaction(List<SecretRule> rules) {
    this.rules = List.copyOf(rules);
  }

  /** 이 저장소가 가리는 자격 증명 전부. */
  public static Redaction standard() {
    return Standard.INSTANCE;
  }

  /** 텍스트의 자격 증명을 {@link #MASK}로 바꾼 텍스트. 가릴 것이 없으면 같은 문자열이다. */
  @Override
  public String apply(String text) {
    String out = text;
    for (SecretRule rule : rules) {
      out = rule.apply(out);
    }
    return out;
  }
}
