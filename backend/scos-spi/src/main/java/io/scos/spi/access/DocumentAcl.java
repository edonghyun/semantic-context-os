package io.scos.spi.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 원천에서 관측한 문서 접근 상태. 모든 구성 요소는 null을 허용하지 않는다. */
public record DocumentAcl(
    String documentId, Status status, Set<AccessKey> keys, Instant observedAt) {
  public DocumentAcl {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(observedAt, "observedAt");
    keys = Set.copyOf(keys);
    if (status == Status.UNSUPPORTED
        && keys.stream().anyMatch(key -> key.kind() != AccessKey.Kind.SCOPE))
      throw new IllegalArgumentException("UNSUPPORTED ACL requires scope keys");
  }

  public enum Status {
    OK,
    UNKNOWN,
    FAILED,
    UNSUPPORTED
  }

  public boolean visibleTo(Set<AccessKey> requester) {
    return (status == Status.OK || status == Status.UNSUPPORTED)
        && keys.stream().anyMatch(requester::contains);
  }

  /** 상태와 정렬한 키의 길이 접두 표현을 해시한다. 관측 시각과 문서 id는 포함하지 않는다. */
  public String fingerprint() {
    String canonical =
        status.name()
            + keys.stream()
                .map(AccessKey::toString)
                .sorted()
                .map(key -> key.length() + ":" + key)
                .collect(Collectors.joining());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JDK must provide SHA-256", e);
    }
  }
}
