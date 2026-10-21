package io.scos.collection.internal.memory;

import io.scos.collection.internal.SnapshotShrinkGuardContract;
import org.junit.jupiter.api.DisplayName;
import org.springframework.transaction.support.TransactionOperations;

/** {@link SnapshotShrinkGuardContract}의 메모리 포트 판(트랜잭션 없음 — 원자성은 PostgreSQL 판 밖의 별도 테스트). */
@DisplayName("스냅샷 축소 보호 계약 — 메모리 포트 판(ADR-0028)")
class MemorySnapshotShrinkGuardTest extends SnapshotShrinkGuardContract {
  @Override
  protected Ports newPorts() {
    return new Ports(
        TransactionOperations.withoutTransaction(),
        new MemoryWorkClaimPort(),
        new MemoryRunStore(),
        new MemoryRawItemStore(),
        new MemoryOutboxPort(),
        new MemorySourceChangeLog(),
        new MemorySourceInstanceStore(),
        new MemorySnapshotGuardStore());
  }
}
