package io.scos.collection.internal;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** 원본 ACL의 시간·비관측·동시 상충 정책. 저장 구현은 이 결정만 원자적으로 적용한다. */
public record RawAclDecision(RawAcl.Observed next, boolean semanticChange) {
  public static RawAclDecision of(
      SourceInstanceId source, Optional<RawAcl.Observed> previous, RawAcl acl, Instant fallbackAt) {
    if (!new AccessKeyInstances().admits(source))
      throw new IllegalArgumentException("Invalid access source instance");
    RawAcl.Observed incoming =
        switch (acl) {
          case RawAcl.NotObserved ignored ->
              previous.orElseGet(
                  () ->
                      RawAcl.observed(
                          Status.UNKNOWN, Set.of(), fallbackAt.truncatedTo(ChronoUnit.MICROS)));
          case RawAcl.Observed observed -> normalized(source, observed);
        };
    RawAcl.Observed next = previous.map(old -> resolve(old, incoming)).orElse(incoming);
    boolean changed = previous.isEmpty() || !sameMeaning(previous.orElseThrow(), next);
    return new RawAclDecision(next, changed);
  }

  /** 저장 불변식 방어다. 원천의 지원 모델·허용 키 정책은 수집 경계 RawAclValidation이 소유한다. */
  private static RawAcl.Observed normalized(SourceInstanceId source, RawAcl.Observed acl) {
    Instant at = acl.observedAt().truncatedTo(ChronoUnit.MICROS);
    if (acl.keys().stream().anyMatch(key -> !key.instance().equals(source))
        || (acl.status() == Status.UNSUPPORTED
            && acl.keys().stream().anyMatch(key -> key.kind() != AccessKey.Kind.SCOPE)))
      return RawAcl.failed(AccessFailureReason.INVALID_CARRIER, at);
    return switch (acl.status()) {
      case UNKNOWN -> RawAcl.observed(acl.status(), Set.of(), at);
      case FAILED -> RawAcl.failed(acl.reason(), at);
      case OK, UNSUPPORTED -> RawAcl.observed(acl.status(), acl.keys(), at);
    };
  }

  private static RawAcl.Observed resolve(RawAcl.Observed old, RawAcl.Observed incoming) {
    int order = incoming.observedAt().compareTo(old.observedAt());
    if (order < 0) return old;
    if (order > 0 || sameMeaning(old, incoming)) return incoming;
    if (old.status() != Status.OK || incoming.status() != Status.OK) {
      if (incoming.status() == Status.FAILED) return incoming;
      if (old.status() == Status.FAILED) return old;
      return RawAcl.failed(AccessFailureReason.CONFLICTING_OBSERVATIONS, old.observedAt());
    }
    Set<AccessKey> intersection = new HashSet<>(old.keys());
    intersection.retainAll(incoming.keys());
    return RawAcl.observed(Status.OK, intersection, old.observedAt());
  }

  private static boolean sameMeaning(RawAcl.Observed first, RawAcl.Observed second) {
    return first.status() == second.status() && first.keys().equals(second.keys());
  }
}
