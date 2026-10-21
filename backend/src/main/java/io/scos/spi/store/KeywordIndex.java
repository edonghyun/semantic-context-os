package io.scos.spi.store;

import java.util.List;

/**
 * 키워드(어휘) 색인 포트(CONTRACTS B3·ADR-0014). 점수 수식은 어댑터가 소유하고 계약이 아니다. 계약({@code KeywordIndexContract})은
 * 순서 성질만 정한다:
 *
 * <ul>
 *   <li>질의 토큰(공백 분리)을 포함하는 유닛이 포함하지 않는 유닛보다 앞선다.
 *   <li>정확 표기 매치가 공백 소실(붙여쓰기) 매치보다 앞선다.
 *   <li>동점은 적재 순서(seq). 필터(archived·partition)는 top-k 이전. 다른 공간의 유닛은 보이지 않는다.
 *   <li>한국어 2글자 질의에 그 토큰을 포함하는 유닛이 결과에 있다.
 *   <li>{@link IndexCapabilities#consistency()}가 TRANSACTIONAL이면 upsert 뒤 즉시 보이고, EVENTUAL이면 결국
 *       보인다.
 * </ul>
 */
public interface KeywordIndex {
  IndexCapabilities capabilities();

  List<ScoredRef> search(SpaceId space, String text, int k, SearchFilter filter);

  void upsert(SpaceId space, List<IndexedText> units);

  void delete(SpaceId space, List<String> unitIds);
}
