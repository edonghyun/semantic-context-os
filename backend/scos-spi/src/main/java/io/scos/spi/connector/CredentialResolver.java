package io.scos.spi.connector;

/**
 * credentialRef → 비밀값. ref는 `<scheme>:<참조>`(`env:`·`file:`·`command:`, TS와 같은 등록값)이고 제품 구현은
 * `credentials.SchemeCredentialResolver`다(ADR-0022 §2). 실패는 비밀값을 싣지 않은 {@link
 * CallFailureException}이다.
 */
public interface CredentialResolver {
  /** 자격 증명 실패의 {@link CallFailureException#operation()}. run 실패 종류를 TS 값으로 남길 때 이것으로 가린다. */
  String OPERATION = "credentials";

  String resolve(String credentialRef);

  /**
   * HTTP 헤더에 실을 자격 증명(Bearer·Basic 토큰): 헤더로 보낼 수 없는 문자(줄바꿈·NUL·그 밖의 제어 문자·0xFF를 넘는 문자 — {@link
   * HeaderValues#isSendable})가 들면 설정 오류로 거부한다(값은 싣지 않는다). 해석은 여러 줄 값을 그대로 돌려준다 — 서비스 계정 JSON처럼 헤더가
   * 아닌 곳에 쓰는 자격 증명이 있다. 헤더 지점에서 거르지 않으면 JDK가 값 전체를 담은 예외를 낸다.
   */
  static String headerValue(String value) {
    if (!HeaderValues.isSendable(value))
      throw new CallFailureException(
          OPERATION,
          CallFailure.of(
              CallFailure.Kind.INVALID_CONFIGURATION,
              "credential value contains a character that cannot be sent in an HTTP header"));
    return value;
  }
}
