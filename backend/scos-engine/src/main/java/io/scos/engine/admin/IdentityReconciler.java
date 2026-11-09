package io.scos.engine.admin;

import io.scos.spi.connector.CancellationToken;
import io.scos.spi.source.SourceInstanceId;

/** 사람 Principal 없이 호출하는 system 전용 정비 경계. HTTP 관리 명령에는 노출하지 않는다. */
public interface IdentityReconciler {
  /** 미등록 소스는 변화가 없다. 소스 처리 실패는 전부 롤백하고 failedSources에 센다. */
  ReconcileReport reconcile(SourceInstanceId source);

  /** 소스별로 커밋하며 한 소스 실패 뒤에도 계속한다. 취소되면 다음 소스를 시작하지 않는다. */
  ReconcileReport reconcileAll(CancellationToken cancel);

  /** 커밋된 연결·해제와 실패한 소스 수. unchanged는 관측 신원 중 변화가 없는 수이며 미관측 연결은 집계하지 않는다. 신원·오류 원문은 포함하지 않는다. */
  record ReconcileReport(int linked, int unlinked, int unchanged, int failedSources) {}
}
