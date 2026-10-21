package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceOwnershipException;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.UnitStoreContract;
import io.scos.support.PgLocks;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** 유닛 저장소 어댑터: 계약 + 어댑터 고유 성질(A2 문서 소유 경합이 행 잠금으로 직렬화된다, TESTING §7 결정적 경합). */
@Tag("db")
@DisplayName("PostgreSQL 유닛 저장소는 A2 소유 경합을 행 잠금으로 직렬화한다(TESTING §7)")
class PostgresUnitStoreTest extends UnitStoreContract {
  /** 경합 단계 하나(점유·커밋·B의 결과)를 기다리는 상한. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  DataSource ds;
  ExecutorService pool;
  CountDownLatch release;

  @BeforeEach
  void clean() {
    PostgresSupport.truncateAll();
    ds = PostgresSupport.dataSource();
  }

  /** 단언이 실패해도 A의 트랜잭션·스레드가 남지 않게. */
  @AfterEach
  void tearDown() {
    if (release != null) release.countDown();
    if (pool != null) pool.shutdownNow();
  }

  @Override
  protected UnitStore newStore() {
    return new PostgresUnitStore(new NamedParameterJdbcTemplate(PostgresSupport.dataSource()));
  }

  /** B의 쓰기(Future)와, A가 커밋하기 전에 B가 이미 끝나 있었는지. */
  record Contention(Future<?> b, boolean finishedBeforeCommit) {}

  @Test
  @DisplayName("미커밋 소유 선점에 막힌 다른 공간의 쓰기는 커밋 전에 끝나지 않고, 커밋 뒤 거부된다")
  void contendedClaimFromOtherSpaceIsRejectedAfterCommit() throws Exception {
    // when: B는 서버에서 A에 블록되고, A가 커밋한다
    Contention contention = contend("a", "b");

    // then
    assertThat(contention.finishedBeforeCommit()).as("B는 A 커밋 전에 끝나지 않는다").isFalse();
    assertThatThrownBy(() -> contention.b().get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(SpaceOwnershipException.class);
  }

  @Test
  @DisplayName("같은 공간의 B는 A 커밋 전에 끝나지 않고, 커밋 뒤 성공한다(대조군)")
  void contendedClaimFromSameSpaceSucceeds() throws Exception {
    // when
    Contention contention = contend("a", "a");
    contention.b().get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);

    // then
    assertThat(contention.finishedBeforeCommit()).as("B는 A 커밋 전에 끝나지 않는다").isFalse();
    assertThat(store.getMany(new SpaceId("a"), List.of("a-1", "b-1")))
        .extracting(ContextUnit::id)
        .containsExactly("a-1", "b-1");
  }

  /**
   * A: 트랜잭션 안에서 같은 문서를 upsert하고 release까지 미커밋 대기. B: 다른/같은 공간으로 같은 문서 upsert. B의 백엔드가 A의 pid에 블록된
   * 것을 서버에서 확인한 뒤, 그때 B가 끝나 있었는지 기록하고 A를 커밋한다.
   */
  private Contention contend(String spaceA, String spaceB) throws Exception {
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    CountDownLatch claimed = new CountDownLatch(1);
    release = new CountDownLatch(1);
    AtomicInteger pidA = new AtomicInteger();
    pool = Executors.newFixedThreadPool(2);
    Future<?> a =
        pool.submit(
            () ->
                tx.execute(
                    status -> {
                      Connection c = DataSourceUtils.getConnection(ds); // 트랜잭션에 묶인 연결
                      try {
                        pidA.set(PgLocks.backendPid(c));
                      } catch (SQLException e) {
                        throw new IllegalStateException(e);
                      } finally {
                        DataSourceUtils.releaseConnection(c, ds);
                      }
                      new PostgresUnitStore(new NamedParameterJdbcTemplate(ds))
                          .upsert(new SpaceId(spaceA), List.of(unit("a-1", "contested")));
                      claimed.countDown();
                      try {
                        if (!release.await(30, TimeUnit.SECONDS))
                          throw new IllegalStateException("release gate was never opened");
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }));
    if (!claimed.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("given 준비 실패: A가 미커밋으로 소유를 선점해야 한다");
    Future<?> b =
        pool.submit(
            () ->
                new PostgresUnitStore(new NamedParameterJdbcTemplate(ds))
                    .upsert(new SpaceId(spaceB), List.of(unit("b-1", "contested"))));

    PgLocks.awaitBlockedBy(ds, pidA.get(), WAIT_LIMIT);

    boolean finishedBeforeCommit = b.isDone();
    release.countDown();
    a.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    return new Contention(b, finishedBeforeCommit);
  }
}
