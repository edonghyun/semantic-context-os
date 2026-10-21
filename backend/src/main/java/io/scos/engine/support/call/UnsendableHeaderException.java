package io.scos.engine.support.call;

/**
 * 보낼 수 없는 헤더 값({@link ProviderHttp#isSendableHeaderValue}이 거부한 값)을 요청 전에 거부했다. 메시지에는 헤더 이름만 싣는다 —
 * JDK 예외는 값 전체를 담는다. 재시도해도 고쳐지지 않는 설정 오류라 실행기가 분류기보다 먼저 invalid_configuration으로 본다.
 */
public final class UnsendableHeaderException extends IllegalArgumentException {
  public UnsendableHeaderException(String headerName) {
    super("header " + headerName + " contains a character that cannot be sent");
  }
}
