package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.HoldGate;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 변경 로그 쓰기 잠금(ADR-0021)은 트랜잭션의 끝(커밋 직전)에서만 쥔다: 한 소스의 페이지 트랜잭션이 아직 남은 작업을 하는 동안 다른 소스의 페이지 커밋은 그 잠금을
 * 기다리지 않는다 — 짧은 lock_timeout(1s)에서도 55P03으로 실패하지 않는다.
 */
@DisplayName("변경 로그 쓰기 잠금은 커밋 직전에만 쥔다(ADR-0021)")
@Tag("db")
class ChangeLogLockScopeTest {
  static final SourceInstanceId SLOW = new SourceInstanceId("src-slow");
  static final SourceInstanceId OTHER = new SourceInstanceId("src-other");
  static final Duration TTL = Duration.ofSeconds(30);
  static final String SHORT_LOCK_TIMEOUT = "1s";
  static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

  MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  PostgresCollectionConfiguration.Adapters adapters;
  PausingOutbox pausing = new PausingOutbox();

  /** 페이지 트랜잭션 안에서 멈춘 느린 소스의 커밋. */
  CompletableFuture<Long> slow;

  /** 트랜잭션 안에서(변경을 적용한 뒤) 멈추는 Outbox — 느린 소스의 남은 작업을 흉내 낸다. */
  static final class PausingOutbox extends MemoryOutboxPort {
    final HoldGate gate = new HoldGate(WAIT_LIMIT);

    @Override
    public synchronized void requestProjection(SourceInstanceId source, Instant now) {
      gate.hold();
      super.requestProjection(source, now);
    }
  }

  @BeforeEach
  void setUp() {
    PostgresSupport.truncateAll();
    adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
  }

  @AfterEach
  void resume() {
    pausing.gate.release();
  }

  PageCommit pageCommit(
      DataSource ds, PostgresCollectionConfiguration.Adapters a, MemoryOutboxPort outbox) {
    return new PageCommit(
        new TransactionTemplate(new DataSourceTransactionManager(ds)),
        a.claims(),
        a.runs(),
        a.rawItems(),
        a.checkpoints(),
        outbox,
        clock,
        TestMetrics.collection(),
        a.changes(),
        new Progress(clock));
  }

  /** 점유·run을 준비하고 원본 하나를 바꾸는 페이지를 커밋한다. */
  long commitOnePage(PageCommit commit, SourceInstanceId source, String itemId) {
    WorkClaim claim =
        adapters
            .claims()
            .tryClaim(source, new OwnerId("w-" + source.value()), TTL, clock.instant())
            .orElseThrow();
    RunId run = new RunId("run-" + source.value());
    adapters.runs().start(run, source, clock.instant());
    CollectionPage page =
        CollectionPage.ofItems(
            List.of(RawItem.minimal(itemId, "v1", "{}", clock.instant())), new Checkpoint(1, "{}"));
    return commit.commit(claim, run, 0, page).revision();
  }

  void givenSlowSourcePausedInsideItsPageTransaction() {
    PageCommit commit = pageCommit(PostgresSupport.dataSource(), adapters, pausing);
    slow = CompletableFuture.supplyAsync(() -> commitOnePage(commit, SLOW, "slow-1"));
    pausing.gate.awaitHeld("느린 소스가 변경을 적용한 채 트랜잭션 안에 있다");
  }

  static DataSource withLockTimeout(String lockTimeout) {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            PostgresSupport.jdbcUrl(), PostgresSupport.username(), PostgresSupport.password());
    Properties props = new Properties();
    props.setProperty("options", "-c lock_timeout=" + lockTimeout);
    ds.setConnectionProperties(props);
    return ds;
  }

  long whenOtherSourceCommitsWithShortLockTimeout() throws Exception {
    DataSource shortTimeout = withLockTimeout(SHORT_LOCK_TIMEOUT);
    PageCommit other =
        pageCommit(
            shortTimeout,
            PostgresCollectionConfiguration.adapters(shortTimeout),
            new MemoryOutboxPort());
    return CompletableFuture.supplyAsync(() -> commitOnePage(other, OTHER, "other-1"))
        .get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
  }

  long finishSlowSource() throws Exception {
    pausing.gate.release();
    return slow.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
  }

  @Nested
  @DisplayName("느린 소스의 페이지 트랜잭션이 변경을 적용한 채 열려 있으면")
  class SlowSourceInsideTransaction {
    @BeforeEach
    void givenSlowSource() {
      givenSlowSourcePausedInsideItsPageTransaction();
    }

    @Test
    @DisplayName("다른 소스의 커밋은 짧은 lock_timeout 안에 끝난다")
    void otherSourceIsNotBlockedByUnfinishedPageTransaction() throws Exception {
      // when
      long revision = whenOtherSourceCommitsWithShortLockTimeout();

      // then
      assertThat(revision).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 소스가 커밋한 뒤 느린 소스도 이어서 커밋된다")
    void slowSourceCommitsAfterwards() throws Exception {
      // given
      whenOtherSourceCommitsWithShortLockTimeout();

      // when
      long revision = finishSlowSource();

      // then
      assertThat(revision).isEqualTo(1);
    }
  }
}
