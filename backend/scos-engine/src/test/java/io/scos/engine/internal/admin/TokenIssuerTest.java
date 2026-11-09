package io.scos.engine.internal.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.TokenHash;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("토큰 발급기는 비밀 형식과 해시를 소유한다(§6.1)")
class TokenIssuerTest {
  private final TokenIssuer issuer = new TokenIssuer(new SecureRandom());

  @Test
  @DisplayName("발급 토큰은 패딩 없는 base64url의 무작위 32바이트다")
  void issuedTokenHasThirtyTwoRandomBytes() {
    // when
    var secret = issuer.issue();
    // then
    assertThat(secret.value().matches("scos_[A-Za-z0-9_-]{43}")).isTrue();
    assertThat(Base64.getUrlDecoder().decode(secret.value().substring(5))).hasSize(32);
  }

  @Test
  @DisplayName("발급할 때마다 다른 비밀을 만든다")
  void issuanceUsesFreshRandomness() {
    // when
    var first = issuer.issue();
    var second = issuer.issue();
    // then
    assertThat(first.value().equals(second.value())).isFalse();
  }

  @Test
  @DisplayName("발급 비밀의 해시는 반환 원문의 SHA-256이다")
  void issuedSecretCarriesSha256Hash() {
    // when
    var secret = issuer.issue();
    // then
    assertThat(secret.hash().equals(TokenHash.of(secret.value()))).isTrue();
  }

  @Test
  @DisplayName("내부 비밀의 문자열도 원문과 전체 해시를 숨긴다")
  void internalSecretStringRepresentationIsRedacted() {
    // when
    var secret = issuer.issue();
    // then
    assertThat(
            secret.toString().contains(secret.value())
                || secret.toString().contains(secret.hash().value()))
        .isFalse();
  }
}
