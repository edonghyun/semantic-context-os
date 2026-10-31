package io.scos.client.redact;

/**
 * 평문 하나에서 한 종류의 자격 증명을 가린다. 입력은 언제나 평문이다 — JSON 줄의 문자열 경계·이스케이프는 {@link JsonRedaction}이 파서로 풀어 넘기므로
 * 규칙은 JSON 문법을 모른다.
 */
@FunctionalInterface
public interface SecretRule {
  /**
   * 가린 평문. 가린 자리는 {@link Redaction#MASK}이고 키 이름·옵션 이름·따옴표는 남긴다. 가릴 것이 없으면 같은 문자열을 돌려준다. 입력 길이에
   * 선형이다.
   */
  String apply(String text);
}
