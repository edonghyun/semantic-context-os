package io.scos.engine.support.projection;

import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.SpaceId;
import java.util.List;
import java.util.Set;

/**
 * 한 소스의 문서를 공간에 투영한다 — 바뀐 유닛만 저장·재임베딩하고, liveDocumentIds 밖의 문서는 지운다. 엔진 쪽 계약이라 수집(점유·run)을 모른다:
 * 호출자는 쓰기 트랜잭션마다 먼저 실행할 guard를 넘기고, guard가 {@link WriteGuardException}을 던지면 sync가 그대로 전파한다.
 */
public interface SourceSync {
  SyncReport sync(
      SpaceId space,
      String sourceId,
      List<SourceDocument> documents,
      Set<String> liveDocumentIds,
      Runnable guard);
}
