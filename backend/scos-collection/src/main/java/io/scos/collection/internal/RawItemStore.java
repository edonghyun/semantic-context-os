package io.scos.collection.internal;

import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 원본 저장 포트(F4·F5·G1). 모든 연산은 소스 안에서만. {@link #apply}의 의미:
 *
 * <ul>
 *   <li>upsert: 기존 순서가 있고 들어온 순서가 같거나 과거면 unchanged(순서 없이 들어오면 unchanged, scheme 불일치는
 *       IllegalArgumentException). active이고 sourceVersion 같으면 unchanged. 아니면 active 갱신(tombstone 복원
 *       포함).
 *   <li>delete: 과거 순서면 unchanged. 아니면 tombstone(lastSourceVersion = 주어진 것 ?? 기존, deletedAt =
 *       occurredAt ?? observedAt). 없던 키도 tombstone이 된다.
 * </ul>
 */
public interface RawItemStore {
  enum Outcome {
    UPSERTED,
    DELETED,
    UNCHANGED
  }

  record Page(List<RawItem> items, Optional<String> nextCursor) {}

  Outcome apply(SourceInstanceId source, RawMutation mutation);

  Optional<RawRecord> get(SourceInstanceId source, String externalId);

  /**
   * active 항목을 externalId 코드 유닛 순으로(TS는 observedAt 역순·localeCompare — 투영은 순서에 의존하지 않는다). cursor =
   * 직전 페이지의 마지막 externalId.
   */
  Page listActive(SourceInstanceId source, int limit, Optional<String> cursor);

  /**
   * 본문 없는 접근 후보. cutoff가 있으면 그 시각 이하의 관측 중 만료 처리가 끝나지 않은 것만 고른다. 완료된 관측도 대상 문서가 다시 FAILED 이외의 상태가
   * 되고 이 공간·소스가 소유할 수 있으면 반환한다. cutoff 없는 수집 후보에는 완료 여부가 영향을 주지 않는다. kinds가 비면 0행이다.
   */
  Page accessCandidates(
      SourceInstanceId source,
      Set<String> kinds,
      Optional<Instant> cutoff,
      int limit,
      Optional<String> cursor);

  /**
   * 같은 시각의 EXPIRED 관측에 대한 만료 처리를 완료한다. 대상 문서가 없거나 FAILED 또는 다른 공간·소스 소유이면 후보에서 제외한다. 새 관측은 다시 대상이며,
   * 다른 시각·상태의 원본에는 영향을 주지 않는다. 호출자의 만료 트랜잭션에 참여한다.
   */
  void completeAccessExpiry(
      SourceInstanceId source,
      String externalId,
      Instant observedAt,
      SpaceId space,
      Optional<String> document);

  /** 이 소스의 active 항목 수. */
  int count(SourceInstanceId source);

  /** 이 소스의 tombstone 수. */
  int tombstoneCount(SourceInstanceId source);

  /** 이 소스의 active 항목 중 observed에 없는 수 — F5 reconcile이 지울 수(스냅샷 축소 보호, ADR-0028). */
  int countUnobserved(SourceInstanceId source, Set<String> observedExternalIds);

  /**
   * F5 reconcile: observed에 없는 active 항목을 tombstone(observedAt = deletedAt = at, 순서 없음)으로 바꾸고 그
   * external id 목록을 돌려준다(F10 snapshot_absent 기록용). 호출자가 succeeded + complete_snapshot일 때만 부른다(불변식
   * 6).
   */
  List<String> tombstoneUnobserved(
      SourceInstanceId source, Set<String> observedExternalIds, Instant at);

  /** 검증된 부분 스냅샷 부재. provider 순서를 만들지 않고 지정한 active 항목만 정리한다. */
  List<String> tombstoneSelected(SourceInstanceId source, Set<String> externalIds, Instant at);

  /** 저장된 blob 데이터(참조가 있는데 없으면 empty — 누락 blob은 투영 읽기 오류로 드러난다, F9). */
  Optional<byte[]> blob(String sha256);
}
