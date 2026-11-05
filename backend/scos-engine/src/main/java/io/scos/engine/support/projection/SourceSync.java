package io.scos.engine.support.projection;

import io.scos.spi.access.DocumentAcl;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.SpaceId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한 소스의 문서를 공간에 투영한다 — 바뀐 유닛만 저장·재임베딩하고, liveDocumentIds 밖의 문서는 지운다. 엔진 쪽 계약이라 수집(점유·run)을 모른다:
 * 호출자는 쓰기 트랜잭션마다 먼저 실행할 guard를 넘기고, guard가 {@link WriteGuardException}을 던지면 sync가 그대로 전파한다.
 */
public interface SourceSync {
  /**
   * acls는 투영 snapshot 읽기 전 시각을 담은 후보이며, 잠긴 문서 아래 단조 결정 시각을 부여해 본문보다 먼저 커밋한다. 빈 맵은 접근 수집 비활성이다. ACL
   * 저장 실패는 본문 처리 전에 전파하고 다른 출처 문서는 ownershipSkipped로 보고한다.
   */
  SyncReport sync(
      SpaceId space,
      String sourceId,
      List<SourceDocument> documents,
      Set<String> liveDocumentIds,
      Map<String, DocumentAcl> acls,
      Runnable guard);
}
