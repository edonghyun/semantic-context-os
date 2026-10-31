package io.scos.spi.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 제시된 토큰의 SHA-256 저장 값. 형식이 다른 원문도 같은 조회 경로로 보낸다. */
public record TokenHash(String value) {
  public TokenHash {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid token hash");
  }

  public static TokenHash of(String presented) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(presented.getBytes(StandardCharsets.UTF_8));
      return new TokenHash(HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  @Override
  public String toString() {
    return "TokenHash[" + value.substring(0, 8) + "]";
  }
}
