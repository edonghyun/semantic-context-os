package io.scos.store.postgres;

import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.DocumentOwnership.Claim;
import io.scos.spi.store.SpaceId;
import io.scos.support.PgLocks;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 미커밋 첫 소유에 다른 출처가 실제 DB에서 막힌 뒤, 첫 소유의 단일 승자를 확인한다. */
@Tag("db")
final class DocumentOwnershipRaceScenario implements AutoCloseable {
  private static final SpaceId SPACE = new SpaceId("a");
  private static final Duration WAIT = Duration.ofSeconds(10);
  private final DataSource dataSource;
  private final DocumentOwnership ownership;
  private final TransactionTemplate tx;
  private final ExecutorService workers = Executors.newFixedThreadPool(2);
  private final CountDownLatch claimed = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicInteger ownerPid = new AtomicInteger();

  DocumentOwnershipRaceScenario() {
    PostgresSupport.truncateAll();
    dataSource = PostgresSupport.dataSource();
    ownership = new PostgresIndexConfiguration().newDocumentOwnership(dataSource);
    tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  record Contention(Claim winner, Claim loser, boolean loserFinishedBeforeCommit) {}

  Contention contend() throws Exception {
    Future<Claim> winner = workers.submit(this::holdFirstClaim);
    await(claimed);
    Future<Claim> loser =
        workers.submit(() -> tx.execute(status -> ownership.claim(SPACE, "source-b", "doc-1")));
    PgLocks.awaitBlockedBy(dataSource, ownerPid.get(), WAIT);
    boolean finished = loser.isDone();
    release.countDown();
    return new Contention(
        winner.get(10, TimeUnit.SECONDS), loser.get(10, TimeUnit.SECONDS), finished);
  }

  private Claim holdFirstClaim() {
    return tx.execute(
        status -> {
          ownerPid.set(
              JdbcClient.create(dataSource)
                  .sql("SELECT pg_backend_pid()")
                  .query(Integer.class)
                  .single());
          Claim claim = ownership.claim(SPACE, "source-a", "doc-1");
          claimed.countDown();
          await(release);
          return claim;
        });
  }

  private static void await(CountDownLatch gate) {
    try {
      if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("소유 gate 시간 초과");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("소유 gate 대기 중단", interrupted);
    }
  }

  @Override
  public void close() {
    release.countDown();
    workers.shutdownNow();
  }
}
