package io.scos.spi.store;

import java.util.List;
import java.util.Optional;

/**
 * 유닛 저장소 포트(CONTRACTS A·B·D·E의 읽기 + 적재). 모든 조회는 공간 안에서만 답한다. 계약은 {@code UnitStoreContract}.
 *
 * <ul>
 *   <li>적재 순서(seq)는 처음 들어온 자리를 유지한다 — 같은 id를 다시 넣으면 내용만 바뀐다.
 *   <li>{@link #getMany}: 입력 순서 유지, 없는 id 생략, 같은 id는 나온 자리마다.
 *   <li>{@link #listByDocument}: 적재 순서대로 range 구간. invalidated·archived 포함.
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

  void upsert(SpaceId space, List<ContextUnit> units);

  List<ContextUnit> getMany(SpaceId space, List<String> ids);

  List<ContextUnit> listByDocument(SpaceId space, String documentId, Range range);

  List<LinkedUnit> linkedUnits(SpaceId space, List<String> unitIds);

  /** 입력 순서 유지, 없는(또는 다른 공간의) 문서는 생략. */
  List<DocumentInfo> getDocuments(SpaceId space, List<String> documentIds);

  /** 공간 안 capturedAt 문자열 최댓값(invalidated·archived 포함). 유닛이 없으면 empty. */
  Optional<String> latestCapturedAt(SpaceId space);

  /**
   * G5·G6: 공간·출처(sourceId)·문서 안에서 keepIds에 없는 유닛을 지우고 지운 id를 적재 순서로 돌려준다(호출자가 색인도 지운다). 다른 공간·다른
   * 출처의 같은 documentId는 건드리지 않는다.
   */
  List<String> deleteByDocument(
      SpaceId space, String sourceId, String documentId, List<String> keepIds);

  /** 공간·출처 안의 문서 id 목록(첫 적재 순서, 중복 없음) — prune 대조용. */
  List<String> listDocumentIds(SpaceId space, String sourceId);

  /** 공간의 모든 유닛(적재 순서) — 임베딩 세대 백필(G9). */
  List<ContextUnit> listAll(SpaceId space);
}
