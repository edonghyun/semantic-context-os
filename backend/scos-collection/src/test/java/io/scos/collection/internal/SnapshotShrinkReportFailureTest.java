package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceChangeLog;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.LogCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 커밋 뒤 보고(이벤트·지표)가 실패해도 이미 마감한 run을 건드리지 않는다(ADR-0028 §5): 완료는 마감한 run을 돌려주고 error 로그만 남긴다. 던지면 수집
 * 런타임이 끝난 run을 실패로 다시 마감하려다 `collect`가 1로 끝난다.
 */
@DisplayName("축소 보호 보고 실패 — 이미 마감한 run은 그대로 둔다(ADR-0028)")
// 제품 클래스의 공용 로거에 appender를 단다 — 같은 JVM의 다른 테스트가 남긴 줄이 섞이지 않게 혼자 돈다(TESTING §6.3)
@Isolated
class SnapshotShrinkReportFailureTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("tiro-main");
  static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
  static final Duration TTL = Duration.ofSeconds(30);
  static final RunId RUN = new RunId("run-1");

  /** 축소 보호 지표 등록만 실패하는 레지스트리(지표 백엔드 장애 모사). */
  static final class FailingGuardMetricRegistry extends SimpleMeterRegistry {
    @Override
    protected Counter newCounter(Meter.Id id) {
      if (id.getName().equals(CollectionMetrics.SNAPSHOT_RECONCILE_GUARDED))
        throw new IllegalStateException("metrics backend down");
      return super.newCounter(id);
    }
  }

  final MemoryRunStore runs = new MemoryRunStore();
  final MemoryRawItemStore rawItems = new MemoryRawItemStore();
  final MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  final LogCapture log = LogCapture.of(RunCompletion.class);
  final RunCompletion completion =
      new RunCompletion(
          TransactionOperations.withoutTransaction(),
          claims,
          runs,
          rawItems,
          new MemoryOutboxPort(),
          new MemorySourceChangeLog(),
          new SnapshotShrinkGuard(
              new MemorySourceInstanceStore(),
              new MemorySnapshotGuardStore(),
              SnapshotShrinkLimits.DEFAULTS,
              CollectionMetrics.of(new FailingGuardMetricRegistry())));

  @AfterEach
  void detach() {
    log.close();
  }

  @Nested
  @DisplayName("보호된 완료의 보고가 지표에서 실패하면")
  class ReportFails {
    Throwable thrown;

    @BeforeEach
    void givenGuardedCompletionWithFailingMetrics() {
      for (int i = 0; i < 20; i++)
        rawItems.apply(
            SOURCE, new RawMutation.Upsert(RawItem.minimal("note-" + i, "v1", "{}", NOW)));
      WorkClaim claim = claims.tryClaim(SOURCE, new OwnerId("worker"), TTL, NOW).orElseThrow();
      runs.start(RUN, SOURCE, NOW);

      thrown =
          catchThrowable(
              () ->
                  completion.complete(
                      new RunCompletion.Input(
                          claim,
                          RUN,
                          RunStatus.SUCCEEDED,
                          CollectionCoverage.COMPLETE_SNAPSHOT,
                          NOW,
                          0,
                          Optional.empty(),
                          Optional.empty())));
    }

    @Test
    @DisplayName("완료는 예외 없이 끝난다")
    void completesWithoutThrowing() {
      // then
      assertThat(thrown).isNull();
    }

    @Test
    @DisplayName("run은 보호된 succeeded로 남는다")
    void runStaysSucceeded() {
      // then
      assertThat(runs.get(RUN).orElseThrow())
          .extracting(CollectionRun::status, CollectionRun::errorKind)
          .containsExactly(RunStatus.SUCCEEDED, Optional.of("snapshot_shrink_guarded"));
    }

    @Test
    @DisplayName("error collection.snapshot.report_failed를 남긴다")
    void logsReportFailure() {
      // then
      assertThat(log.events("collection.snapshot.report_failed")).hasSize(1);
    }
  }
}
