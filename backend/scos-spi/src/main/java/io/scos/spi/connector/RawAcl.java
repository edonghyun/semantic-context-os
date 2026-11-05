package io.scos.spi.connector;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** 본문과 독립된 원천 ACL 관측. 비관측은 관측된 UNKNOWN과 구분한다. observedAt은 응답을 받은 로컬 시각이다. */
public sealed interface RawAcl {
  /** 상태·키·관측 시각은 null을 허용하지 않고, 키는 불변 복사한다. */
  record Observed(
      DocumentAcl.Status status,
      Set<AccessKey> keys,
      Instant observedAt,
      AccessFailureReason reason)
      implements RawAcl {
    public Observed(DocumentAcl.Status status, Set<AccessKey> keys, Instant observedAt) {
      this(status, keys, observedAt, AccessFailureReason.PROVIDER_FAILURE);
    }

    public Observed {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(observedAt, "observedAt");
      Objects.requireNonNull(reason, "reason");
      if (status != DocumentAcl.Status.FAILED) reason = AccessFailureReason.NONE;
      keys = Set.copyOf(keys);
    }
  }

  /** 이번 경로에서 ACL을 관측하지 않았으며, 기존 관측을 대체하지 않는다. */
  record NotObserved() implements RawAcl {}

  static Observed observed(DocumentAcl.Status status, Set<AccessKey> keys, Instant observedAt) {
    return new Observed(status, keys, observedAt);
  }

  static Observed failed(AccessFailureReason reason, Instant observedAt) {
    return new Observed(DocumentAcl.Status.FAILED, Set.of(), observedAt, reason);
  }

  static RawAcl notObserved() {
    return new NotObserved();
  }
}
