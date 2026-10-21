package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.HeaderValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 헤더 값 검사({@link HeaderValues#isSendable})와 자격 증명을 HTTP 헤더에 싣는 지점({@link
 * CredentialResolver#headerValue}): JDK·undici(TS)가 헤더로 보내지 못하는 문자(HTAB 밖의 제어 문자, 0xFF를 넘는 문자)가 든
 * 값은 설정 오류로 거부하고 값은 싣지 않는다. 해석(스킴)은 여러 줄 값을 그대로 돌려준다 — 헤더가 아닌 곳(서비스 계정 JSON)에 쓰는 자격 증명이 있다.
 */
@DisplayName("헤더 값은 보낼 수 있는 문자만 받고, 자격 증명 거부에 값을 싣지 않는다")
class CredentialHeaderValueTest {
  static final String SECRET = "xoxb-header-secret";

  @Nested
  @DisplayName("HeaderValues로 값을 검사하면")
  class Check {
    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {SECRET, SECRET + "\tpart", " ~", "sécret", "\u0080ÿ"})
    @DisplayName("HTAB·출력 가능한 ASCII·Latin-1 값은 보낼 수 있다")
    void acceptsSendableValue(String value) {
      // when
      boolean sendable = HeaderValues.isSendable(value);

      // then
      assertThat(sendable).isTrue();
    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(
        strings = {
          SECRET + "\r",
          SECRET + "\nX-Injected: y",
          SECRET + "\u0000",
          "\r" + SECRET,
          SECRET + "\u0001",
          SECRET + "\u001f",
          SECRET + "\u007f",
          SECRET + "Ā",
          SECRET + "€"
        })
    @DisplayName("헤더로 보낼 수 없는 문자가 든 값은 보낼 수 없다")
    void rejectsUnsendableCharacters(String value) {
      // when
      boolean sendable = HeaderValues.isSendable(value);

      // then
      assertThat(sendable).isFalse();
    }
  }

  @Nested
  @DisplayName("CredentialResolver.headerValue로 자격 증명을 거르면")
  class Credential {
    @Test
    @DisplayName("보낼 수 있는 값은 그대로 쓴다")
    void passesSendableValue() {
      // when
      String header = CredentialResolver.headerValue(SECRET);

      // then
      assertThat(header).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("보낼 수 없는 값은 invalid_configuration이고, 오류에 값을 싣지 않는다")
    void rejectsWithoutValue() {
      // when
      CallFailureException failure =
          catchThrowableOfType(
              CallFailureException.class, () -> CredentialResolver.headerValue(SECRET + "\r"));

      // then
      assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.INVALID_CONFIGURATION);
      assertThat(failure).hasMessageNotContaining(SECRET);
    }
  }
}
