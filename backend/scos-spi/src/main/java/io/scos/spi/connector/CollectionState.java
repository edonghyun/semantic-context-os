package io.scos.spi.connector;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 실행 소스에 한정한 수집 요약. raw 내용·SQL·blob 접근 권한을 제공하지 않는다. */
public interface CollectionState {
  int MAX_BATCH = 100;

  record Completion(String version, SourceOrder order, Instant completedAt) {}

  record BodySummary(
      boolean active,
      Optional<Completion> completion,
      boolean incomplete,
      boolean verifiedMembership) {
    public boolean isCompleted(String version) {
      return active
          && !incomplete
          && completion.map(c -> c.version().equals(version)).orElse(false);
    }
  }

  Map<String, BodySummary> bodies(
      String fingerprint, List<String> pageIds, CancellationToken cancel);

  /** 같은 범위 세대의 전체 정리가 실제로 완료된 적이 있는가. */
  default boolean reconciliationBaseline(String fingerprint, CancellationToken cancel) {
    if (cancel.isCancelled())
      throw new CallFailureException(
          "collection.state",
          CallFailure.of(CallFailure.Kind.CANCELLED, "Collection state read cancelled"));
    return false;
  }

  record AbsenceCandidate(String externalId, String resourceType) {}

  enum AbsenceStatus {
    AVAILABLE,
    UNAVAILABLE,
    LIMIT_EXCEEDED,
    UNKNOWN_OWNERSHIP
  }

  record AbsenceCandidates(AbsenceStatus status, String token, List<AbsenceCandidate> items) {
    public AbsenceCandidates {
      items = List.copyOf(items);
    }
  }

  /** 현재 실행에 바인딩된 부재 후보. 본문·metadata·SQL은 제공하지 않는다. */
  default AbsenceCandidates absences(CancellationToken cancel) {
    if (cancel.isCancelled())
      throw new CallFailureException(
          "collection.state",
          CallFailure.of(CallFailure.Kind.CANCELLED, "Collection state read cancelled"));
    return new AbsenceCandidates(AbsenceStatus.UNAVAILABLE, "", List.of());
  }

  /** 완료 상태 저장 기능이 없는 직접 호출은 모든 페이지를 다시 읽는다. */
  static CollectionState unavailable() {
    return (fingerprint, ids, cancel) -> {
      if (ids.size() > MAX_BATCH)
        throw new IllegalArgumentException("Collection state batch exceeds limit");
      if (cancel.isCancelled())
        throw new CallFailureException(
            "collection.state",
            CallFailure.of(CallFailure.Kind.CANCELLED, "Collection state read cancelled"));
      return Map.of();
    };
  }
}
