package io.scos.collection.internal.credentials;

/**
 * credentialRef 스킴 하나(`env`·`file`·`command`, ADR-0022 §2). 실패는 비밀값을 싣지 않은 {@link
 * io.scos.spi.connector.CallFailureException}이다.
 */
public interface CredentialScheme {
  /** `<name>:` 접두사의 이름. */
  String name();

  /** 접두사를 뗀 나머지(변수 이름·경로·명령)로 비밀값을 얻는다. */
  String resolve(String reference);
}
