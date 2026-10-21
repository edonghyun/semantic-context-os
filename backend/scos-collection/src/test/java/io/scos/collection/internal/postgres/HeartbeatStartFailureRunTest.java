package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceInstances;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.ManualTaskScheduler;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.task.TaskRejectedException;

/**
 * heartbeat 등록 실패의 PostgreSQL 판(실제 run·점유 저장소): run 시작 뒤 스케줄러가 등록을 거부하면 그 run은 failed로 마감되고 점유는 바로
 * 풀린다. 메모리 판은 {@code collection.CollectionRuntimeHeartbeatStartFailureTest}.
 */
@Tag("db")
@DisplayName("heartbeat 등록이 거부된 run은 실패로 끝나 점유를 바로 푼다(PostgreSQL 판)")
class HeartbeatStartFailureRunTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-heartbeat-start");
  static final String REJECTION = "shutting down";

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("run 시작 뒤 heartbeat 등록이 거부되면")
  class HeartbeatRejected {
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
    Optional<RunStatus> latestStatus;
    boolean nextWorkerClaimed;

    @BeforeAll
    void collect() {
      PostgresSupport.truncateAll();
      DataSource ds = PostgresSupport.dataSource();
      var adapters = PostgresCollectionConfiguration.adapters(ds);
      adapters.sources().save(SourceInstances.noop(SOURCE, clock.instant()));
      Throwable thrown =
          catchThrowable(
              () ->
                  PostgresCollectionRuntime.over(ds, clock)
                      .adapter(SourceInstances.NOOP_ADAPTER)
                      .heartbeats(ManualTaskScheduler.rejecting(REJECTION))
                      .build()
                      .collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none()));
      // 다른 이유로 끝났어도 run은 FAILED, 점유는 풀릴 수 있으므로 원인이 주입한 거부인지 먼저 확인한다
      if (!(thrown instanceof TaskRejectedException) || !REJECTION.equals(thrown.getMessage()))
        throw new IllegalStateException(
            "given 준비 실패: 수집이 heartbeat 등록 거부로 끝나지 않았다 — " + thrown, thrown);
      latestStatus = adapters.runs().latest(SOURCE).map(run -> run.status());
      nextWorkerClaimed =
          adapters
              .claims()
              .tryClaim(
                  SOURCE, new OwnerId("w2"), PostgresCollectionRuntime.LEASE_TTL, clock.instant())
              .isPresent();
    }

    @Test
    @DisplayName("run은 failed로 마감된다")
    void runFailed() {
      // then
      assertThat(latestStatus).contains(RunStatus.FAILED);
    }

    @Test
    @DisplayName("다음 worker가 바로 점유한다")
    void nextWorkerClaims() {
      // then
      assertThat(nextWorkerClaimed).isTrue();
    }
  }
}
