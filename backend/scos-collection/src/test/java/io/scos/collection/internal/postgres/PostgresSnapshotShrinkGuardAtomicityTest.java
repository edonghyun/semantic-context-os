package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SnapshotShrinkLimits;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.WorkClaim;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 스냅샷 축소 보호의 원자성(ADR-0028): 보호 판정 기록·허용 표시 소모는 정리(tombstone)·run 마감·변경 로그와 한 완료 트랜잭션이다. 완료의 마지막
 * 쓰기(변경 로그 append, ADR-0021)가 실패하면 그 트랜잭션의 판정 흔적도 남지 않는다 — 허용 표시가 쓰이지 않은 채 사라지거나, 정리하지 않은 run의 보호
 * 상태가 남지 않는다.
 */
@Tag("db")
@DisplayName("스냅샷 축소 보호 원자성 — 판정 흔적은 완료 트랜잭션과 함께 커밋된다(ADR-0028)")
class PostgresSnapshotShrinkGuardAtomicityTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("tiro-main");
  static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");
  static final Duration TTL = Duration.ofSeconds(30);
  static final RunId RUN = new RunId("run-1");

  PostgresCollectionConfiguration.Adapters adapters;
  RunCompletion completion;
  WorkClaim claim;
  final List<String> ids = new ArrayList<>();

  @BeforeEach
  void assembleWithFailingChangeLog() {
    PostgresSupport.truncateAll();
    DataSource ds = PostgresSupport.dataSource();
    adapters = PostgresCollectionConfiguration.adapters(ds);
    completion =
        new RunCompletion(
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            adapters.claims(),
            adapters.runs(),
            adapters.rawItems(),
            adapters.outbox(),
            new FailingChangeLog(adapters.changes()),
            new SnapshotShrinkGuard(
                adapters.sources(),
                adapters.guards(),
                SnapshotShrinkLimits.DEFAULTS,
                TestMetrics.collection()));
    adapters.sources().save(SourceInstances.noop(SOURCE, T0));
    for (int i = 0; i < 100; i++) {
      String id = String.format("note-%03d", i);
      adapters
          .rawItems()
          .apply(SOURCE, new RawMutation.Upsert(RawItem.minimal(id, "v1", "{}", T0)));
      ids.add(id);
    }
  }

  /** 점유를 잡고 run을 시작해 앞의 observed건만 관측했다. */
  void givenRunObserving(int observed) {
    claim =
        adapters
            .claims()
            .tryClaim(SOURCE, new OwnerId("worker"), TTL, T0)
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: 점유를 잡지 못했다"));
    adapters.runs().start(RUN, SOURCE, T0);
    adapters.runs().observe(RUN, ids.subList(0, observed));
  }

  /** 완료를 시도한다 — 변경 로그 append에서 실패해야 한다. */
  void completeFailingAtLastWrite() {
    Throwable failure =
        catchThrowable(
            () ->
                completion.complete(
                    new RunCompletion.Input(
                        claim,
                        RUN,
                        RunStatus.SUCCEEDED,
                        CollectionCoverage.COMPLETE_SNAPSHOT,
                        T0,
                        0,
                        Optional.empty(),
                        Optional.empty())));
    if (!(failure instanceof IllegalStateException e)
        || !e.getMessage().equals(FailingChangeLog.MESSAGE))
      throw new IllegalStateException("given 준비 실패: 완료가 변경 로그에서 실패하지 않았다", failure);
  }

  @Nested
  @DisplayName("축소를 허용한 소스의 정리 완료가 마지막 쓰기에서 실패하면")
  class AcceptedReconcileFails {
    @BeforeEach
    void givenAcceptedShrinkAndFailedCompletion() {
      adapters.guards().acceptShrink(SOURCE, T0);
      givenRunObserving(49);

      completeFailingAtLastWrite();
    }

    @Test
    @DisplayName("허용 표시는 소모되지 않은 채 남는다")
    void acceptanceStaysPending() {
      // then
      assertThat(adapters.guards().lastAcceptance(SOURCE))
          .hasValueSatisfying(a -> assertThat(a.pending()).isTrue());
    }

    @Test
    @DisplayName("정리한 tombstone도 함께 사라진다")
    void tombstonesRolledBack() {
      // then
      assertThat(adapters.rawItems().count(SOURCE)).isEqualTo(100);
    }

    @Test
    @DisplayName("run은 running으로 남는다")
    void runStaysRunning() {
      // then
      assertThat(adapters.runs().get(RUN).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    }
  }

  @Nested
  @DisplayName("보호 판정 완료가 마지막 쓰기에서 실패하면")
  class GuardedCompletionFails {
    @BeforeEach
    void givenGuardedAndFailedCompletion() {
      givenRunObserving(0);

      completeFailingAtLastWrite();
    }

    @Test
    @DisplayName("보호 상태를 남기지 않는다")
    void recordsNoGuardedState() {
      // then
      assertThat(adapters.guards().lastGuarded(SOURCE)).isEmpty();
    }

    @Test
    @DisplayName("run은 running으로 남는다")
    void runStaysRunning() {
      // then
      assertThat(adapters.runs().get(RUN).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    }
  }
}
