package io.scos.collection.internal.projection;

import io.scos.collection.internal.AccessCollection;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.AccessModel;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 권한 운반 원본의 상태·키를 검증한 뒤 교집합을 계산한다. I/O와 시각 결정은 호출자가 소유한다. */
final class DocumentAcls {
  private final AccessCollection.Options options;

  DocumentAcls() {
    this(AccessCollection.Options.DEFAULTS);
  }

  DocumentAcls(AccessCollection.Options options) {
    this.options = options;
  }

  /** 빈 carrier·불확실한 관측은 FAILED다. Notion의 미지원 선언만 원본 없이 인스턴스 scope를 받는다. */
  DocumentAcl calculate(
      SourceDescriptor source,
      SourceAdapter adapter,
      String documentId,
      List<RawItem> items,
      Instant decidedAt) {
    if (!new AccessKeyInstances().admits(source.id()))
      return failed(documentId, decidedAt, AccessFailureReason.INVALID_INSTANCE);
    if (adapter.accessPolicy().model() == AccessModel.UNSUPPORTED) {
      if (!adapter.accessPolicy().permitsScope(source))
        return failed(documentId, decidedAt, AccessFailureReason.UNSUPPORTED_SOURCE);
      var scope = new AccessKey(AccessKey.Kind.SCOPE, source.id(), "*");
      return new DocumentAcl(documentId, Status.UNSUPPORTED, Set.of(scope), decidedAt);
    }

    List<RawAcl> carriers =
        items.stream().filter(adapter.accessPolicy()::carries).map(RawItem::acl).toList();
    boolean expired =
        items.stream()
            .filter(adapter.accessPolicy()::refreshes)
            .anyMatch(
                item ->
                    item.acl() instanceof RawAcl.Observed acl
                        && !acl.observedAt().isAfter(decidedAt.minus(options.maxAge())));
    if (expired) return failed(documentId, decidedAt, AccessFailureReason.EXPIRED);
    if (carriers.isEmpty()) return failed(documentId, decidedAt, AccessFailureReason.NO_CARRIERS);
    for (var carrier : carriers)
      if (!validCarrier(carrier, source.id()))
        return failed(documentId, decidedAt, failureReason(carrier));

    Set<AccessKey> keys = new HashSet<>(((RawAcl.Observed) carriers.getFirst()).keys());
    for (RawAcl carrier : carriers) keys.retainAll(((RawAcl.Observed) carrier).keys());
    return new DocumentAcl(documentId, Status.OK, keys, decidedAt);
  }

  private DocumentAcl failed(String document, Instant at, AccessFailureReason reason) {
    return new DocumentAcl(document, Status.FAILED, Set.of(), at, reason);
  }

  private AccessFailureReason failureReason(RawAcl acl) {
    return switch (acl) {
      case RawAcl.NotObserved ignored -> AccessFailureReason.UNOBSERVED_CARRIER;
      case RawAcl.Observed observed ->
          observed.status() == Status.FAILED
              ? observed.reason()
              : AccessFailureReason.INVALID_CARRIER;
    };
  }

  private boolean validCarrier(RawAcl acl, SourceInstanceId instance) {
    return switch (acl) {
      case RawAcl.NotObserved ignored -> false;
      case RawAcl.Observed observed ->
          observed.status() == Status.OK
              && observed.keys().stream()
                  .allMatch(
                      key -> key.instance().equals(instance) && key.kind() != AccessKey.Kind.SCOPE);
    };
  }
}
