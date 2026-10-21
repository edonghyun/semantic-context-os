package io.scos.server;

import io.scos.collection.internal.BlobGarbageCollector;
import io.scos.collection.internal.Maintenance;
import io.scos.engine.support.text.EventLog;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 데몬의 정비 한 번(TS source-service-runner.runMaintenanceIfDue): 끝나면 info `maintenance.sweep.completed`,
 * 실패하면 error `maintenance.sweep.failed`를 남기고 던지지 않는다 — 데몬은 다음 예정 시각을 그대로 전진시킨다(실패 때마다 사이클마다 다시 도는
 * 폭주를 막는다). detail의 blob 수치는 TS와 같고, 변경 로그 보존 수는 `changesPruned`다(TS의 inboxPruned·rejectionsPruned
 * 자리 — Java에는 이벤트 inbox가 없고 거부 기록 보존은 정하지 않았다).
 */
final class DaemonMaintenance implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(DaemonMaintenance.class);
  private final Supplier<Maintenance.Report> sweep;

  DaemonMaintenance(Supplier<Maintenance.Report> sweep) {
    this.sweep = sweep;
  }

  @Override
  public void run() {
    Maintenance.Report report;
    try {
      report = sweep.get();
    } catch (RuntimeException e) {
      EventLog.error(log, "maintenance.sweep.failed")
          .detail(
              EventLog.detail(
                  "message", e.getMessage() == null ? "Maintenance sweep failed" : e.getMessage()))
          .cause(e)
          .log();
      return;
    }
    BlobGarbageCollector.Report blobs = report.blobs();
    EventLog.info(log, "maintenance.sweep.completed")
        .detail(
            EventLog.detail(
                "blob",
                EventLog.detail(
                    "scanned",
                    blobs.scanned(),
                    "deleted",
                    blobs.deleted(),
                    "retainedByReference",
                    blobs.retainedByReference(),
                    "retainedByGrace",
                    blobs.retainedByGrace(),
                    "bytesFreed",
                    blobs.bytesFreed()),
                "changesPruned",
                report.prunedChanges()))
        .log();
  }
}
