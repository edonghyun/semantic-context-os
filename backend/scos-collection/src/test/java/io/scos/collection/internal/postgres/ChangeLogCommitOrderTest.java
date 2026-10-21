package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.Eventually;
import io.scos.support.HoldGate;
import io.scos.support.PgLocks;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F10 커서 소비자는 변경을 건너뛰지 않는다(ADR-0021): 먼저 seq를 받은 트랜잭션이 늦게 커밋돼도, 그 사이에 읽은 소비자의 다음 읽기에 그 변경이 나온다. 두
 * 트랜잭션의 엇갈린 커밋을 서버 측 조건(pg_blocking_pids)으로 결정적으로 만든다.
 */
@DisplayName("F10 커서 소비자는 늦게 커밋된 앞 seq도 받는다(ADR-0021)")
@Tag("db")
class ChangeLogCommitOrderTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final Instant AT = Instant.parse("2026-10-14T00:00:00Z");
  static final int PAGE = 100;
  static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

  DataSource ds;
  SourceChangeLog changes;
  TransactionTemplate tx;

  /** 첫 트랜잭션을 커밋 전에 붙잡는다. */
  final HoldGate firstBeforeCommit = new HoldGate(WAIT_LIMIT);

  final AtomicInteger firstPid = new AtomicInteger();

  @BeforeEach
  void setUp() {
    PostgresSupport.truncateAll();
    ds = PostgresSupport.dataSource();
    changes = PostgresCollectionConfiguration.adapters(ds).changes();
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  @AfterEach
  void releaseGate() {
    firstBeforeCommit.release();
  }

  /** 첫 트랜잭션: 변경 "first"를 append하고 커밋 전에 멈춘다. */
  CompletableFuture<Void> givenFirstTransactionAppendedButNotCommitted() throws Exception {
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      append("first");
                      firstPid.set(backendPidOfCurrentTransaction());
                      firstBeforeCommit.hold();
                    }));
    firstBeforeCommit.awaitHeld("첫 트랜잭션이 append한 채 열려 있다");
    return first;
  }

  /** 둘째 트랜잭션: 변경 "second"를 append하고 커밋한다(첫 트랜잭션에 막히면 막힌 채로 둔다). */
  CompletableFuture<Void> whenSecondTransactionCommits() throws Exception {
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> append("second")));
    Eventually.await(
        "둘째 트랜잭션이 커밋됐거나 첫 트랜잭션에 막혔다",
        () -> second.isDone() || PgLocks.anyBlockedBy(ds, firstPid.get()),
        WAIT_LIMIT);
    return second;
  }

  List<String> consumeAllInTwoReads(CompletableFuture<?>... pending) throws Exception {
    List<String> seen = new ArrayList<>();
    SourceChangeLog.Page early = changes.read(Optional.empty(), 0, PAGE);
    early.changes().forEach(c -> seen.add(c.externalId().orElseThrow()));
    firstBeforeCommit.release();
    for (CompletableFuture<?> f : pending) f.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    changes
        .read(Optional.empty(), early.nextSeq(), PAGE)
        .changes()
        .forEach(c -> seen.add(c.externalId().orElseThrow()));
    return seen;
  }

  @Test
  @DisplayName("먼저 seq를 받은 트랜잭션이 늦게 커밋돼도 커서 소비자는 두 변경을 모두 받는다")
  void consumerSeesLateCommittedLowerSeq() throws Exception {
    // given
    CompletableFuture<Void> first = givenFirstTransactionAppendedButNotCommitted();

    // when
    CompletableFuture<Void> second = whenSecondTransactionCommits();
    List<String> seen = consumeAllInTwoReads(first, second);

    // then
    assertThat(seen).containsExactlyInAnyOrder("first", "second");
  }

  void append(String externalId) {
    changes.appendAll(
        List.of(
            new SourceChangeLog.Entry(
                SOURCE,
                Optional.of(externalId),
                SourceChangeLog.Kind.UPSERT,
                Optional.of("v1"),
                Optional.empty(),
                AT)));
  }

  int backendPidOfCurrentTransaction() {
    Connection c = DataSourceUtils.getConnection(ds);
    try {
      return PgLocks.backendPid(c);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    } finally {
      DataSourceUtils.releaseConnection(c, ds);
    }
  }
}
