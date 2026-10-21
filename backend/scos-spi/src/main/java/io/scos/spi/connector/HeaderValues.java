package io.scos.spi.connector;

/**
 * HTTP 헤더 값 검사(JDK만 쓴다): 커넥터의 자격 증명 검사({@code CredentialResolver.headerValue})와 HTTP 호출의 마지막
 * 방어({@code ProviderHttp})가 같이 쓴다. 커넥터 SPI가 공급자 호출 모듈을 모르게 여기 둔다.
 */
public final class HeaderValues {
  private HeaderValues() {}

  /**
   * 헤더로 보낼 수 있는 값인가: HTAB, 0x20–0x7E, 0x80–0xFF만. JDK HttpRequest와 TS undici가 받는 문자 집합이다 — 그
   * 밖(줄바꿈·NUL·그 밖의 제어 문자·0xFF를 넘는 문자)은 JDK가 값 전체를 담은 예외로 거부한다.
   */
  public static boolean isSendable(String value) {
    return value
        .chars()
        .allMatch(c -> c == '\t' || (c >= 0x20 && c <= 0x7E) || (c >= 0x80 && c <= 0xFF));
  }
}
