package io.scos.engine.internal.admin;

import io.scos.spi.access.IssuedToken;
import io.scos.spi.access.TokenHash;
import java.security.SecureRandom;

/** 원문과 저장 해시의 조합은 관리 구현 밖으로 전달하지 않는다. */
final class TokenIssuer {
  private final SecureRandom random;

  TokenIssuer(SecureRandom random) {
    this.random = random;
  }

  IssuedSecret issue() {
    var token = IssuedToken.generate(random);
    return new IssuedSecret(token.value(), token.hash());
  }

  record IssuedSecret(String value, TokenHash hash) {
    @Override
    public String toString() {
      return "IssuedSecret[redacted]";
    }
  }
}
