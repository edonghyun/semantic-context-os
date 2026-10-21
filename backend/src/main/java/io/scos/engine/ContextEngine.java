package io.scos.engine;

import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;

/**
 * 읽기 경로 파사드(단계 2 §1.1): MCP 도구 4종과 1:1로 대응한다. 스레드 안전하다. 모든 질의는 묻는 사람({@link Principal})을 첫 인자로 받는다
 * — 0.1.0은 접근 필터 없이 검색 경로에 전달만 한다(다중 사용자 접근 설계 §8). 인자 범위를 벗어나면 {@link InvalidQueryException}, 계약
 * 위반은 {@link ContextEngineException} 하위로 던지고, 인프라 예외는 감싸지 않는다(리뷰 M-5).
 */
public interface ContextEngine {
  /** search_context. */
  SearchResult search(Principal principal, SearchQuery query);

  /** read_document. */
  DocumentPage read(Principal principal, ReadRequest request);

  /** related_documents. */
  RelatedResult related(Principal principal, RelatedQuery query);

  /** assemble_context. 최신성을 결과에 함께 싣는다. */
  ContextBundle assemble(Principal principal, AssembleQuery query);

  /** 공간 데이터의 나이. 결과마다 실리지만 단독으로도 연다(ops 게이지·status). */
  Freshness freshness(Principal principal, SpaceId space);

  /** 질의가 공간을 주지 않을 때 쓰는 공간. */
  SpaceId defaultSpace();

  /** 조립을 시작한다(단계 2 §1.1). */
  static ContextEngineBuilder builder() {
    return new ContextEngineBuilder();
  }
}
