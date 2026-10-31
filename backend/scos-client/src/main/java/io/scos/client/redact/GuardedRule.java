package io.scos.client.redact;

import java.util.List;
import java.util.function.Predicate;

/**
 * 같은 종류의 규칙 조각 묶음 앞에 값싼 사전 검사를 둔다: {@code mayMatch}가 거짓인 텍스트(조각이 반드시 포함하는 글자가 없는 텍스트)에는 정규식을 하나도
 * 돌리지 않는다. 조각은 {@link Redaction}이 차례로 적용한다.
 */
final class GuardedRule implements SecretRule {
  private final Predicate<String> mayMatch;
  private final Redaction rules;

  GuardedRule(Predicate<String> mayMatch, List<SecretRule> rules) {
    this.mayMatch = mayMatch;
    this.rules = new Redaction(rules);
  }

  @Override
  public String apply(String text) {
    return mayMatch.test(text) ? rules.apply(text) : text;
  }
}
