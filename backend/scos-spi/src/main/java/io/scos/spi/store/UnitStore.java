package io.scos.spi.store;

import java.util.List;
import java.util.Optional;

/**
 * 유닛 읽기 포트(CONTRACTS A·B·D·E). 모든 조회는 공간 안에서만 답한다. 계약은 {@code UnitStoreContract}.
 *
 * <ul>
 *   <li>읽기는 요청 범위의 허용 문서만 포함한다. 제한 범위의 빈 키·ACL 부재는 빈 결과다.
 *   <li>{@link #getMany}: 입력 순서 유지, 없는 id 생략, 같은 id는 나온 자리마다.
 *   <li>{@link #listByDocument}: 적재 순서대로 range 구간. invalidated·archived 포함. 제한 범위는 COMPACTED를 제외한 뒤
 *       offset·limit을 적용한다. 무제한은 압축 유닛도 포함한다.
 *   <li>{@link #linkedUnits}: 입력 유닛들의 링크 대상 문서의 유닛을 **대상 유닛의 적재 순서**로, 입력 id 전부 제외, 중복 없음.
 *   <li>문서 정보: title·uri·sourceId는 첫 유닛(적재 순서), latestCapturedAt은 capturedAt 문자열 최댓값.
 * </ul>
 */
public interface UnitStore {
  record Range(int offset, int limit) {
    public Range {
      if (offset < 0 || limit < 0) throw new IllegalArgumentException("offset/limit must be >= 0");
    }
  }

  record LinkedUnit(String unitId, String documentId) {}

  record DocumentInfo(
      String documentId,
      String title,
      String uri,
      String sourceId,
      int unitCount,
      Optional<String> latestCapturedAt) {}

  List<ContextUnit> getMany(SpaceId space, AccessScope access, List<String> ids);

  List<ContextUnit> listByDocument(
      SpaceId space, AccessScope access, String documentId, Range range);

  /** 입력 유닛에서 연결된 대상 문서의 접근을 판정한다. */
  List<LinkedUnit> linkedUnits(SpaceId space, AccessScope access, List<String> unitIds);

  /** 입력 순서 유지, 없는(또는 다른 공간의) 문서는 생략. */
  List<DocumentInfo> getDocuments(SpaceId space, AccessScope access, List<String> documentIds);
}
