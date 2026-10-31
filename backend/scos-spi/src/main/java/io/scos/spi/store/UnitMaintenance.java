package io.scos.spi.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 적재·유지보수·운영 조회 전용 포트(범위 없음). 읽기 출구는 UnitStore·VectorIndex를 쓴다. 허용 호출처는
 * ArchitectureRulesTest.unitMaintenanceOnlyFromAllowedCallers로 제한한다. 공간 인자를 받는 연산은 지정 공간 안에서만 동작한다.
 * 계약은 UnitMaintenanceContract.
 */
public interface UnitMaintenance {
  /** 모든 공간의 COMPACTED 유닛 수. 비어 있으면 0. 기동 시 다중 사용자 모드의 압축 유입을 검사한다. */
  long countCompacted();

  /** 같은 id를 다시 넣으면 내용만 바꾸고 처음 적재 순서를 유지한다. 다른 공간 소유 문서는 배치 전체를 거부한다. */
  void upsert(SpaceId space, List<ContextUnit> units);

  /** 적재 diff: 입력 순서 유지, 없는 id 생략, 같은 id는 나온 자리마다. */
  List<ContextUnit> previousUnits(SpaceId space, List<String> ids);

  /** 임베딩 재사용 판정용 저장 벡터. 없는 id는 생략한다. */
  Map<String, double[]> storedVectors(SpaceId space, List<String> unitIds);

  /** 공간·출처·문서 안에서 keepIds 밖 유닛을 지우고 지운 id를 적재 순서로 돌려준다(G5·G6). */
  List<String> deleteByDocument(
      SpaceId space, String sourceId, String documentId, List<String> keepIds);

  /** 공간·출처의 문서 id를 첫 적재 순서로 중복 없이 돌려준다(prune 대조용). */
  List<String> listDocumentIds(SpaceId space, String sourceId);

  /** 공간의 모든 유닛을 적재 순서로 돌려준다(재임베딩). */
  List<ContextUnit> listAll(SpaceId space);

  /** capturedAt 문자열 최댓값(invalidated·archived 포함). 비어 있으면 empty. */
  Optional<String> latestCapturedAt(SpaceId space);
}
