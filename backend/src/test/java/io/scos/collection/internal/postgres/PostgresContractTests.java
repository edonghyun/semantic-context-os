package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CheckpointContract;
import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.OutboxContract;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RawItemStoreContract;
import io.scos.collection.internal.RunStore;
import io.scos.collection.internal.RunStoreContract;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.collection.internal.SourceChangeLogContract;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceInstanceStoreContract;
import io.scos.collection.internal.SupervisorScheduleContract;
import io.scos.collection.internal.WorkClaimContract;
import io.scos.collection.internal.WorkClaimPort;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/** P-1: PostgreSQL 어댑터가 메모리 참조 구현과 같은 계약을 통과한다. */
@DisplayName("PostgreSQL 어댑터는 메모리와 같은 포트 계약을 지킨다(P-1)")
@Tag("db")
class PostgresContractTests {
  @BeforeEach
  void clean() {
    PostgresSupport.truncateAll();
  }

  @Nested
  @DisplayName("작업 점유는 소스마다 한 owner만 fencing token으로 가진다(불변식 5)")
  class WorkClaim extends WorkClaimContract {
    @Override
    protected WorkClaimPort newPort() {
      return PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).claims();
    }
  }

  @Nested
  @DisplayName("체크포인트는 기대 revision이 맞을 때만 전진한다(불변식 2)")
  class Checkpoint extends CheckpointContract {
    @Override
    protected CheckpointPort newPort() {
      return PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).checkpoints();
    }
  }

  @Nested
  @DisplayName("투영 요청(Outbox)은 소스당 한 행이고 version 조건으로 끝난다")
  class Outbox extends OutboxContract {
    @Override
    protected OutboxPort newPort() {
      return PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).outbox();
    }
  }

  @Nested
  @DisplayName("원본 저장소는 mutation·reconcile·blob 의미를 지킨다(F4·F5·F8·G1)")
  class RawItems extends RawItemStoreContract {
    @Override
    protected RawItemStore newStore() {
      return PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).rawItems();
    }
  }

  @Nested
  @DisplayName("run 저장소는 run 기록·관측·스냅샷 완료를 소스별로 둔다")
  class Runs extends RunStoreContract {
    @Override
    protected RunStore newStore() {
      return PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).runs();
    }

    @Override
    protected Optional<byte[]> storedBlob(String sha256) {
      return PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource())
          .rawItems()
          .blob(sha256);
    }
  }

  @Nested
  @DisplayName("변경 로그는 seq 순으로 쌓이고 커서로 읽힌다(F10)")
  class Changes extends SourceChangeLogContract {
    @Override
    protected SourceChangeLog newLog() {
      return PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).changes();
    }
  }

  @Nested
  @DisplayName("소스 등록 저장소는 id별 등록 하나를 둔다")
  class Sources extends SourceInstanceStoreContract {
    @Override
    protected SourceInstanceStore newStore() {
      return PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).sources();
    }
  }

  @Nested
  @DisplayName("감독은 소스별 수집·스냅샷 주기로 due를 정한다(ADR-0022 §3)")
  class Schedules extends SupervisorScheduleContract {
    @Override
    protected Ports newPorts() {
      var adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
      return new Ports(adapters.sources(), adapters.runs(), adapters.claims());
    }
  }
}
