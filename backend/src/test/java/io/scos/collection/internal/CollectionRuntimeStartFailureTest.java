package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.ManualTaskScheduler;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 점유 뒤 run 시작이 실패하면 점유를 즉시 돌려준다 — 다음 수집이 TTL을 기다리지 않는다(메모리 포트, 트랜잭션 없음). */
@DisplayName("run 시작 실패는 점유를 즉시 돌려준다(메모리 포트)")
class CollectionRuntimeStartFailureTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");

  /** start만 실패시키는 RunStore. */
  static final class FailingStartRunStore extends MemoryRunStore {
    @Override
    public synchronized CollectionRun start(RunId id, SourceInstanceId source, Instant startedAt) {
      throw new IllegalStateException("injected run start failure");
    }
  }

  MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  ManualTaskScheduler scheduler = new ManualTaskScheduler();

  /** run 시작이 실패하는 런타임으로 한 번 수집하고 던져진 예외를 돌려준다. */
  Throwable collectWithFailingRunStart() {
    sources.save(SourceInstances.noop(SOURCE, clock.instant()));
    CollectionRuntime runtime =
        MemoryCollectionRuntime.over(clock, sources, claims, new FailingStartRunStore())
            .heartbeats(scheduler)
            .build();
    return catchThrowable(
        () -> runtime.collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none()));
  }

  /** 다른 worker(w2)가 이 소스를 점유해 본다. */
  Optional<WorkClaim> claimByAnotherWorker() {
    return claims.tryClaim(
        SOURCE, new OwnerId("w2"), MemoryCollectionRuntime.LEASE_TTL, clock.instant());
  }

  @Nested
  @DisplayName("점유 뒤 run 시작이 실패하면")
  class RunStartFails {
    @Test
    @DisplayName("실패는 그대로 전파된다")
    void propagatesStartFailure() {
      // when
      Throwable thrown = collectWithFailingRunStart();

      // then
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("injected run start failure");
    }

    @Test
    @DisplayName("점유를 바로 비활성으로 돌린다")
    void releasesClaim() {
      // when
      collectWithFailingRunStart();

      // then
      assertThat(claims.current(SOURCE).orElseThrow().isActiveAt(clock.instant())).isFalse();
    }

    @Test
    @DisplayName("다음 worker가 바로 점유한다")
    void nextWorkerClaimsImmediately() {
      // given
      collectWithFailingRunStart();

      // when
      Optional<WorkClaim> next = claimByAnotherWorker();

      // then
      assertThat(next).isPresent();
    }

    @Test
    @DisplayName("heartbeat를 등록하지 않는다")
    void registersNoHeartbeat() {
      // when
      collectWithFailingRunStart();

      // then
      assertThat(scheduler.tasks()).isEmpty();
    }
  }
}
