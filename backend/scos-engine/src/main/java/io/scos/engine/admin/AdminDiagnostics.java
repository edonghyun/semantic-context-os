package io.scos.engine.admin;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.AccessCost;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 읽기 전용 관리 진단. 저장된 접근 상태와 실제 읽기 경로의 사용자 판정을 전달하며 변경하지 않는다. */
public interface AdminDiagnostics {
  List<SourceAccessView> sourceAccess();

  Optional<DocumentAccessView> explain(String documentId, Optional<String> userId);

  /** 본문 결과와 접근 결과는 독립적이다. 비용은 같은 run의 측정값만 포함하며 없으면 미측정이다. */
  record SourceAccessView(
      SourceInstanceId source,
      String kind,
      Optional<String> bodyResult,
      Optional<String> result,
      Optional<Instant> finishedAt,
      int rawUnknown,
      int rawFailed,
      int documentsMissingAcl,
      int rawUnobserved,
      int rawStale,
      Optional<Instant> oldestObservedAt,
      boolean running,
      int consecutiveIncomplete,
      Optional<Instant> lastCompleteAt,
      int documentsHidden,
      Set<String> unreadableFiles,
      Optional<RunCost> cost) {
    public SourceAccessView {
      unreadableFiles = Set.copyOf(unreadableFiles);
    }
  }

  record RunCost(String runId, Instant recordedAt, AccessCost cost) {}

  record CarrierAccess(String externalId, RawAcl acl) {}

  /** 첫 소유·문서 결정·carrier 원인과 선택한 사용자의 실제 키를 묶은 관리자 전용 상세다. */
  record DocumentAccessView(
      String documentId,
      SourceInstanceId source,
      SpaceId space,
      Optional<DocumentAcl> acl,
      List<CarrierAccess> carriers,
      Optional<UserAccessView> user) {
    public DocumentAccessView {
      carriers = List.copyOf(carriers);
    }
  }

  /** links가 비면 연결 부재, unobserved 경고가 있으면 수동 연결은 있으나 관측이 없는 상태다. */
  record UserAccessView(
      String id,
      boolean known,
      Set<AccessKey> keys,
      Set<AccessKey> intersection,
      boolean visible,
      List<IdentityLink> links) {
    public UserAccessView {
      keys = Set.copyOf(keys);
      intersection = Set.copyOf(intersection);
      links = List.copyOf(links);
    }
  }
}
