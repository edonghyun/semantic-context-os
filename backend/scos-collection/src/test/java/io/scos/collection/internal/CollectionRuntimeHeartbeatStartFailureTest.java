package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.LogCapture;
import io.scos.support.ManualTaskScheduler;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.core.task.TaskRejectedException;

/**
 * run 시작 뒤 heartbeat 등록이 실패하면(종료 중 스케줄러의 거부) 그 run을 그 자리에서 failed로 마감하고 점유를 돌려준다 — 다음 수집의 stale
 * 정리까지 running으로 남지 않는다(F6 리뷰 B n4·공용 리뷰 m3, 메모리 포트).
 */
@DisplayName("run 시작 뒤 heartbeat 등록이 거부되면")
// 제품 클래스의 공용 로거에 appender를 단다 — 같은 JVM의 다른 테스트가 남긴 줄이 섞이지 않게 혼자 돈다(TESTING §6.3)
@Isolated
class CollectionRuntimeHeartbeatStartFailureTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final String REJECT_REASON = "shutting down";
  static final String FINISH_FAILURE = "injected finish failure";

  final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
  final MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  final MemoryRunStore runs = new MemoryRunStore();
  final LogCapture log = LogCapture.of(CollectionRuntime.class);

  @AfterEach
  void closeLog() {
    log.close();
  }

  Throwable collectWithRejectingScheduler() {
    return collectWith(runs);
  }

  Throwable collectWith(MemoryRunStore runStore) {
    MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
    sources.save(SourceInstances.noop(SOURCE, clock.instant()));
    CollectionRuntime runtime =
        MemoryCollectionRuntime.over(clock, sources, claims, runStore)
            .heartbeats(ManualTaskScheduler.rejecting(REJECT_REASON))
            .build();
    return catchThrowable(
        () -> runtime.collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none()));
  }

  /** 다른 worker(w2)가 이 소스를 점유해 본다. */
  Optional<WorkClaim> claimByAnotherWorker() {
    return claims.tryClaim(
        SOURCE, new OwnerId("w2"), MemoryCollectionRuntime.LEASE_TTL, clock.instant());
  }

  @Test
  @DisplayName("거부는 그대로 전파된다")
  void propagates() {
    // when
    Throwable thrown = collectWithRejectingScheduler();

    // then
    assertThat(thrown).isInstanceOf(TaskRejectedException.class);
  }

  @Test
  @DisplayName("run은 failed로 마감된다")
  void runFinishedAsFailed() {
    // when
    collectWithRejectingScheduler();

    // then
    assertThat(runs.latest(SOURCE).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
  }

  @Test
  @DisplayName("다음 worker가 바로 점유한다")
  void nextWorkerClaimsImmediately() {
    // given
    collectWithRejectingScheduler();

    // when
    Optional<WorkClaim> next = claimByAnotherWorker();

    // then
    assertThat(next).isPresent();
  }

  @Test
  @DisplayName("마감된 run은 거부 예외의 종류와 메시지를 남긴다")
  void recordsKindAndSummary() {
    // when
    collectWithRejectingScheduler();

    // then
    CollectionRun run = runs.latest(SOURCE).orElseThrow();
    assertThat(List.of(run.errorKind(), run.errorSummary()))
        .containsExactly(Optional.of("TaskRejectedException"), Optional.of(REJECT_REASON));
  }

  @Test
  @DisplayName("error collection.run.failed를 남긴다")
  void logsRunFailed() {
    // when
    collectWithRejectingScheduler();

    // then
    assertThat(log.events("collection.run.failed"))
        .singleElement()
        .satisfies(
            line ->
                assertThat(line.get("detail").get("errorKind").asString())
                    .isEqualTo("TaskRejectedException"));
  }

  @Nested
  @DisplayName("run 마감마저 실패하면")
  class FinishAlsoFails {
    final MemoryRunStore failingFinish =
        new MemoryRunStore() {
          @Override
          public synchronized CollectionRun finish(
              RunId id,
              RunStatus status,
              CollectionCoverage coverage,
              Instant finishedAt,
              int errorCount,
              Optional<String> errorKind,
              Optional<String> errorSummary,
              int reconciled) {
            throw new IllegalStateException(FINISH_FAILURE);
          }
        };

    @Test
    @DisplayName("마감 실패를 원 예외의 suppressed로 붙인다")
    void attachesSuppressed() {
      // when
      Throwable thrown = collectWith(failingFinish);

      // then
      assertThat(thrown.getSuppressed())
          .singleElement()
          .satisfies(s -> assertThat(s).hasMessage(FINISH_FAILURE));
    }

    @Test
    @DisplayName("그래도 점유를 바로 돌려준다")
    void stillReleasesClaim() {
      // given
      collectWith(failingFinish);

      // when
      Optional<WorkClaim> next = claimByAnotherWorker();

      // then
      assertThat(next).isPresent();
    }
  }
}
