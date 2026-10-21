package io.scos.collection.internal;

import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 원본 저장 포트(F4·F5·G1). 모든 연산은 소스 안에서만. {@link #apply}의 의미(TS applyMutation):
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
  java.util.List<String> tombstoneUnobserved(
      SourceInstanceId source, Set<String> observedExternalIds, Instant at);

  /** 저장된 blob 데이터(참조가 있는데 없으면 empty — 누락 blob은 투영 읽기 오류로 드러난다, F9). */
  Optional<byte[]> blob(String sha256);
}
