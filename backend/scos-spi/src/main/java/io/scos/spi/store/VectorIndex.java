package io.scos.spi.store;

import java.util.List;

/**
 * 벡터 색인 포트(CONTRACTS B4·ADR-0014). 계약은 {@code VectorIndexContract}가 정의한다.
 *
 * <ul>
 *   <li>코사인 유사도, {@code score > 0}만, 점수 내림차순, 동점은 적재 순서.
 *   <li>길이가 다른 벡터·영벡터(행·질의)는 0점으로 제외된다.
 *   <li>필터(archived·partition)는 top-k 이전에 적용된다. 다른 공간의 유닛은 어떤 경로로도 보이지 않는다.
 *   <li>정확 어댑터는 참조 구현과 상대 오차 1e-6 안에서 같은 점수를 내고 동점군(≤1e-12) 밖 순서가 같다(ADR-0014 P-3: pgvector
 *       float4); 근사 어댑터는 {@link IndexCapabilities#approximate()}를 선언한다.
 * </ul>
 */
public interface VectorIndex {
  IndexCapabilities capabilities();

  /** queries[i] → 결과[i]. */
  List<List<ScoredRef>> search(SpaceId space, List<double[]> queries, int k, SearchFilter filter);

  /** 같은 unitId는 덮어쓴다(적재 순서는 처음 들어온 자리 유지). TRANSACTIONAL 어댑터는 호출자 트랜잭션에 참여한다. */
  void upsert(SpaceId space, List<IndexedUnit> units);

  void delete(SpaceId space, List<String> unitIds);

  /** 저장된 벡터를 돌려준다(문서→문서 검색의 시드 벡터). 없는 id는 생략. 근사·float4 어댑터는 저장 정밀도 그대로. */
  java.util.Map<String, double[]> vectorsOf(SpaceId space, List<String> unitIds);
}
