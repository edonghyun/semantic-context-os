package io.scos.engine;

import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.SpaceId;
import java.util.List;
import java.util.Set;

/**
 * 공개 쓰기 표면. 문서를 유닛으로 투영해 저장한다 — 바뀐 유닛만 쓰고, {@code liveDocumentIds} 밖의 문서는 지우며, 문서 하나의 실패는 다른 문서에
 * 번지지 않는다. 호출자가 한 번에 하나씩 부른다(스레드 안전하지 않다). 임베딩 세대가 다르면 {@link
 * EmbeddingGenerationMismatchException}이 그대로 전파된다.
 */
public interface ContextIngestor {
  /**
   * 문서를 투영해 저장하고 결과를 돌려준다.
   *
   * @param sourceId 이 호출이 동기화하는 원천
   * @param documents 이번에 읽은 문서
   * @param liveDocumentIds 원천에 현존하는 문서 id — 이 밖의 저장 문서는 지운다
   */
  IngestReport ingest(
      SpaceId space, String sourceId, List<SourceDocument> documents, Set<String> liveDocumentIds);
}
