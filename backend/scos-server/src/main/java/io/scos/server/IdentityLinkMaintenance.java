package io.scos.server;

import io.scos.engine.EventLog;
import io.scos.engine.admin.IdentityReconciler;
import io.scos.engine.admin.IdentityReconciler.ReconcileReport;
import io.scos.engine.support.logging.EventValues;
import io.scos.spi.connector.CancellationToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 데몬 루프 전용. blob 정비 설정과 독립된 24h 일정이며 재시작의 첫 tick에 누락을 회복한다. */
final class IdentityLinkMaintenance {
  private static final Logger log = LoggerFactory.getLogger(IdentityLinkMaintenance.class);
  // 자동 연결은 사용자 설정 maintenanceInterval과 무관하게 하루에 한 번 실행한다(§7.3).
  private static final Duration INTERVAL = Duration.ofHours(24);
  private final IdentityReconciler reconciler;
  private final Clock clock;
  private @Nullable Instant nextDue;

  IdentityLinkMaintenance(IdentityReconciler reconciler, Clock clock) {
    this.reconciler = reconciler;
    this.clock = clock;
  }

  void tick(CancellationToken cancel) {
    if (cancel.isCancelled()) return;
    Instant now = clock.instant();
    if (nextDue != null && now.isBefore(nextDue)) return;
    // 실패도 다음 날까지 기다린다. 소스별 커밋·취소는 reconciler가 소유한다.
    nextDue = now.plus(INTERVAL);
    ReconcileReport report;
    try {
      report = reconciler.reconcileAll(cancel);
    } catch (RuntimeException failure) {
      // 저장소 예외의 이메일·권한 키를 로그에 복사하지 않는다.
      report = new ReconcileReport(0, 0, 0, 1);
    }
    var event =
        report.failedSources() == 0
            ? EventLog.info(log, "admin.identity_reconcile.completed")
            : EventLog.warn(log, "admin.identity_reconcile.failed");
    event
        .detail(
            EventValues.detail(
                "linked",
                report.linked(),
                "unlinked",
                report.unlinked(),
                "unchanged",
                report.unchanged(),
                "failedSources",
                report.failedSources()))
        .log();
  }
}
