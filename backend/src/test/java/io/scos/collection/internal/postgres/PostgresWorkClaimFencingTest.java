package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

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
import io.scos.support.PgLocks;
import io.scos.support.TestMetrics;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 불변식 5의 직렬화: 페이지 트랜잭션이 점유 행을 `FOR UPDATE`로 잡고 있는 동안 다른 worker의 tryClaim은 서버에서 블록되고, 페이지가 커밋된 뒤에야
 * 점유를 가져간다 — "점유를 가진 동안의 쓰기만 커밋된다". 결정적 경합: pg_blocking_pids 조건 대기.
 */
@Tag("db")
@Timeout(120)
@DisplayName("페이지 트랜잭션이 점유 행을 잠근 동안 다른 점유는 기다린다(불변식 5)")
class PostgresWorkClaimFencingTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final Duration TTL = Duration.ofSeconds(30);
  static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("A의 페이지 트랜잭션 중 점유가 만료돼 B가 점유를 시도하면")
  class ClaimDuringPageTransaction {
    DataSource ds;
    PostgresCollectionConfiguration.Adapters adapters;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
    GatedOutbox gated;

    /** 실패해도 A의 트랜잭션이 남지 않게 게이트를 연다. */
    @AfterAll
    void openGate() {
      if (gated != null) gated.proceed.countDown();
    }

    /** 트랜잭션 안(점유 검사 뒤)에서 멈추는 Outbox: A의 pid를 기록하고 게이트가 열릴 때까지 기다린다. */
    final class GatedOutbox extends MemoryOutboxPort {
      final CountDownLatch inTx = new CountDownLatch(1);
      final CountDownLatch proceed = new CountDownLatch(1);
      final AtomicInteger pid = new AtomicInteger();

      @Override
      public synchronized void requestProjection(SourceInstanceId source, Instant now) {
        try {
          Connection c = DataSourceUtils.getConnection(ds); // 트랜잭션에 묶인 연결
          pid.set(PgLocks.backendPid(c));
          DataSourceUtils.releaseConnection(c, ds);
          inTx.countDown();
          // 유계 대기: 테스트가 게이트를 못 열면 트랜잭션을 롤백으로 끝낸다(스위트 hang 대신 실패)
          if (!proceed.await(60, TimeUnit.SECONDS))
            throw new IllegalStateException("gate was never opened");
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
    }

    WorkClaim a;
    boolean bBlockedWhileATransactionOpen;
    long aRevision;
    Optional<WorkClaim> b;

    /**
     * A가 점유 검사를 지나 페이지 트랜잭션 안(게이트)에서 멈춘 동안 시계상 A의 점유를 만료시키고 B가 tryClaim한다. B가 서버에서 A에 블록된 것을
     * pg_blocking_pids로 확인한 뒤 게이트를 연다 — 게이트가 이 계약의 조건이다(TESTING §2.5 규칙 1 예외).
     */
    @BeforeAll
    void claimWhilePageTransactionOpen() throws Exception {
      PostgresSupport.truncateAll();
      ds = PostgresSupport.dataSource();
      adapters = PostgresCollectionConfiguration.adapters(ds);
      a =
          adapters
              .claims()
              .tryClaim(SOURCE, new OwnerId("A"), TTL, clock.instant())
              .orElseThrow(() -> new IllegalStateException("given 준비 실패: A가 점유하지 못했다"));
      RunId run = new RunId("run-a");
      adapters.runs().start(run, SOURCE, clock.instant());
      gated = new GatedOutbox();
      PageCommit commit =
          new PageCommit(
              new TransactionTemplate(new DataSourceTransactionManager(ds)),
              adapters.claims(),
              adapters.runs(),
              adapters.rawItems(),
              adapters.checkpoints(),
              gated,
              clock,
              TestMetrics.collection(),
              adapters.changes(),
              new Progress(clock));
      CollectionPage page =
          CollectionPage.ofItems(
              List.of(RawItem.minimal("i1", "v1", "{}", clock.instant())), new Checkpoint(1, "{}"));
      CompletableFuture<Long> aCommit =
          CompletableFuture.supplyAsync(() -> commit.commit(a, run, 0, page).revision());
      if (!gated.inTx.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: A가 점유 검사를 지나 트랜잭션 안에 있지 않다");
      clock.advance(TTL.plusSeconds(1)); // A의 점유는 시계상 만료 — B가 가져갈 수 있어야 하지만 행 잠금이 막는다
      CompletableFuture<Optional<WorkClaim>> bClaim =
          CompletableFuture.supplyAsync(
              () -> adapters.claims().tryClaim(SOURCE, new OwnerId("B"), TTL, clock.instant()));
      PgLocks.awaitBlockedBy(ds, gated.pid.get(), WAIT_LIMIT);
      bBlockedWhileATransactionOpen = !bClaim.isDone();
      gated.proceed.countDown();
      aRevision = aCommit.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
      b = bClaim.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A의 페이지 트랜잭션이 점유 행을 잠근 동안 B의 tryClaim은 블록된다")
    void claimWaitsForPageTransaction() {
      // then
      assertThat(bBlockedWhileATransactionOpen).isTrue();
    }

    @Test
    @DisplayName("A의 페이지는 점유를 가진 채 커밋된다")
    void aCommitsWithClaim() {
      // then
      assertThat(aRevision).isEqualTo(1);
      assertThat(adapters.rawItems().count(SOURCE)).isEqualTo(1);
    }

    @Test
    @DisplayName("A가 커밋한 뒤에 B가 다음 token으로 점유를 얻는다")
    void bClaimsAfterCommit() {
      // then
      assertThat(b)
          .hasValueSatisfying(
              claim -> assertThat(claim.fencingToken()).isEqualTo(a.fencingToken() + 1));
    }
  }
}
