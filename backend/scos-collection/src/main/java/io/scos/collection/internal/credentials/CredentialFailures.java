package io.scos.collection.internal.credentials;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;

/** 자격 증명 실패. 메시지에는 스킴·변수 이름·경로·종료 코드만 싣고, 원인 예외(출력·내용을 담을 수 있다)는 붙이지 않는다. */
final class CredentialFailures {
  static final String OPERATION = CredentialResolver.OPERATION;

  private CredentialFailures() {}

  static CallFailureException invalid(String message) {
    return new CallFailureException(
        OPERATION, CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, message));
  }

  static CallFailureException cancelled(String message) {
    return new CallFailureException(OPERATION, CallFailure.of(CallFailure.Kind.CANCELLED, message));
  }
}
