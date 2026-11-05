package io.scos.spi.source;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 원천에서 관측한 신원. 이메일을 읽지 못한 경우에도 원천 사용자 식별자는 보존한다. observedAt은 응답을 받은 로컬 시각이다. */
public record SourceIdentityRecord(
    String sourceUserId,
    Optional<String> email,
    boolean emailVerified,
    boolean guest,
    boolean deleted,
    Instant observedAt) {
  public SourceIdentityRecord {
    Objects.requireNonNull(sourceUserId, "sourceUserId");
    Objects.requireNonNull(email, "email");
    Objects.requireNonNull(observedAt, "observedAt");
  }
}
