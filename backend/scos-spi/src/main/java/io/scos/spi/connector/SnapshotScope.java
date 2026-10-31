package io.scos.spi.connector;

import java.util.Map;
import java.util.Set;

/** 추가 부재 검증이 필요한 범위 세대와 직접 재조회할 원본 종류. */
public record SnapshotScope(
    String fingerprint,
    Set<String> directResourceTypes,
    Map<String, ReconciliationKind> reconciliationKinds) {
  public SnapshotScope(String fingerprint, Set<String> directResourceTypes) {
    this(fingerprint, directResourceTypes, Map.of());
  }

  public SnapshotScope(String fingerprint, Map<String, ReconciliationKind> reconciliationKinds) {
    this(fingerprint, reconciliationKinds.keySet(), reconciliationKinds);
  }

  // 단계 1의 보수적 원격 검증 대상 한도. 운영 비용 측정 뒤에만 조정한다.
  public static final int MAX_CANDIDATES = 100;

  public SnapshotScope {
    directResourceTypes = Set.copyOf(directResourceTypes);
    reconciliationKinds = Map.copyOf(reconciliationKinds);
    if (fingerprint == null
        || fingerprint.isBlank()
        || directResourceTypes.isEmpty()
        || directResourceTypes.stream().anyMatch(String::isBlank))
      throw new IllegalArgumentException("Invalid snapshot verification scope");
  }
}
