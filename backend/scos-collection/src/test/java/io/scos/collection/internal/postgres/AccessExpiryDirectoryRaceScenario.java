package io.scos.collection.internal.postgres;

import io.scos.collection.internal.postgres.AccessExpiryPostgresTest.ExpiryScenario;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.PgLocks;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 소스를 점유한 수집과 디렉터리를 읽은 sweep을 실제 행 잠금 경계에서 겹친다. */
@Tag("db")
final class AccessExpiryDirectoryRaceScenario implements AutoCloseable {
  private final ExpiryScenario scenario = new ExpiryScenario();
  private final ExecutorService workers = Executors.newFixedThreadPool(2);
  private final CountDownLatch sourceLocked = new CountDownLatch(1);
  private final CountDownLatch directoryRead = new CountDownLatch(1);
  private final CountDownLatch releaseExpiry = new CountDownLatch(1);
  private final AtomicInteger expiryPid = new AtomicInteger();
  private final AtomicBoolean firstRead = new AtomicBoolean(true);

  AccessExpiryDirectoryRaceScenario() {
    PostgresSupport.truncateAll();
    scenario.givenDirectory();
  }

  void collectDuringExpiry() throws Exception {
    var collection = workers.submit(this::collectWithSourceLock);
    await(sourceLocked);
    var expiry = workers.submit(() -> scenario.sweep(observedDirectory()));
    await(directoryRead);
    PgLocks.awaitBlockedBy(PostgresSupport.dataSource(), expiryPid.get(), Duration.ofSeconds(10));
    releaseExpiry.countDown();
    collection.get(10, TimeUnit.SECONDS);
    expiry.get(10, TimeUnit.SECONDS);
  }

  private void collectWithSourceLock() {
    var tx =
        new TransactionTemplate(new DataSourceTransactionManager(PostgresSupport.dataSource()));
    tx.executeWithoutResult(
        status -> {
          var source = scenario.adapters.sources().get(ExpiryScenario.SLACK).orElseThrow();
          scenario.adapters.sources().requireCollectionConfiguration(source.descriptor());
          sourceLocked.countDown();
          await(directoryRead);
          var current = scenario.directory.current(ExpiryScenario.SLACK);
          scenario.directory.save(
              ExpiryScenario.SLACK,
              new SourceAccessDirectory.Snapshot(
                  current.identities(),
                  Map.of("C1", new GroupSnapshot("C1", Set.of("U2"), ExpiryScenario.NOW))));
        });
  }

  private SourceAccessDirectory observedDirectory() {
    return new SourceAccessDirectory() {
      @Override
      public Snapshot current(SourceInstanceId source) {
        var current = scenario.directory.current(source);
        if (firstRead.getAndSet(false)) {
          expiryPid.set(
              JdbcClient.create(PostgresSupport.dataSource())
                  .sql("SELECT pg_backend_pid()")
                  .query(Integer.class)
                  .single());
          directoryRead.countDown();
          await(releaseExpiry);
        }
        return current;
      }

      @Override
      public void save(SourceInstanceId source, Snapshot snapshot) {
        scenario.directory.save(source, snapshot);
      }
    };
  }

  Set<String> members() {
    return scenario.directory.current(ExpiryScenario.SLACK).groups().get("C1").members();
  }

  private static void await(CountDownLatch gate) {
    try {
      if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("만료 경합 gate 시간 초과");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("만료 경합 gate 대기 중단", interrupted);
    }
  }

  @Override
  public void close() {
    releaseExpiry.countDown();
    directoryRead.countDown();
    workers.shutdownNow();
  }
}
