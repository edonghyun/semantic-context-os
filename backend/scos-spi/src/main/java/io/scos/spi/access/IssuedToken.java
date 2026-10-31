package io.scos.spi.access;

import java.security.SecureRandom;
import java.util.Base64;

/** 발급 순간에만 명시적으로 꺼내는 토큰 원문. */
public final class IssuedToken {
  // 설계 §6.1의 256비트 토큰.
  private static final int TOKEN_BYTES = 32;
  private final String value;

  private IssuedToken(String value) {
    this.value = value;
  }

  public static IssuedToken generate(SecureRandom random) {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return new IssuedToken("scos_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
  }

  public String value() {
    return value;
  }

  public TokenHash hash() {
    return TokenHash.of(value);
  }

  @Override
  public String toString() {
    return "scos_…" + hash().value().substring(0, 8);
  }
}
