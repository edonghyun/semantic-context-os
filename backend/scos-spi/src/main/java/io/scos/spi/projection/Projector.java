package io.scos.spi.projection;

import io.scos.spi.connector.RawItem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 커넥터 종류(kind) 하나의 투영기(ADR-0022): 소스의 active 원본을 문서로 묶고, 문서마다 엔진에 넣을 {@link SourceDocument}를 만든다.
 * 수집 어댑터가 kind마다 하나인 것처럼 투영기도 kind마다 하나다.
 */
public interface Projector {
  /** 원본 콘텐츠(inline 값 또는 blob)를 읽는다. empty면 본문 없음. */
  interface ContentReader extends Function<RawItem, Optional<String>> {}

  /** 이 투영기가 맡는 소스 종류(`source_instances.kind`). */
  String kind();

  /**
   * 이 kind의 원본을 documentId별로 묶는다. 순서는 documentId 코드 유닛 순(정렬된 Map)이다. 이 kind가 아닌 원본은 버린다.
   *
   * <p>문서 ACL은 묶음의 carrier로만 계산한다. AccessPolicy가 선언한 같은 문서를 제한하는 모든 원본을 함께 넣어야 한다. 런타임은 누락·분할을 검사하며
   * 위반 문서를 FAILED로 닫는다.
   */
  Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items);

  /** 문서 하나를 만든다. 담을 본문이 없으면 empty(투영 건너뜀으로 센다). */
  Optional<SourceDocument> project(
      String sourceId, String documentId, List<RawItem> items, ContentReader reader);
}
