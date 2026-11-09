package io.scos.engine.admin;

import java.util.UUID;

/** 커밋 뒤 발급 응답에서 한 번 전달하는 원문. 영속화하거나 로그에 기록하지 않는다. */
public record IssuedToken(UUID tokenId, String value) {
  @Override
  public String toString() {
    return "IssuedToken[tokenId=" + tokenId + ", value=redacted]";
  }
}
