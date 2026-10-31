package io.scos.collection.internal.postgres;

import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.ConfigurationFenceScenario;
import io.scos.support.PgLocks;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 실제 별도 연결의 설정 UPDATE가 검증 행 잠금에 막혔음을 서버에서 확인한다. */
@Tag("db")
final class PostgresConfigurationRace {
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
  private final CountDownLatch bodyWritten = new CountDownLatch(1);
  private final CountDownLatch releaseBody = new CountDownLatch(1);
  private final AtomicInteger bodyPid = new AtomicInteger();
  private final CommitContext.Ports ports;
  private final ConfigurationFenceScenario context;
  private final JdbcClient jdbc;

  PostgresConfigurationRace() {
    PostgresSupport.truncateAll();
    var dataSource = PostgresSupport.dataSource();
    ports = CommitContext.Ports.postgres(dataSource);
    jdbc = JdbcClient.create(dataSource);
    context = new ConfigurationFenceScenario(ports, new PostgresBodyCompletionStore(jdbc));
    context.previousBody();
  }

  boolean changeDuringBodyTransaction() throws Exception {
    var body =
        CompletableFuture.runAsync(
            () ->
                ports
                    .tx()
                    .executeWithoutResult(
                        status -> {
                          context.completeBody();
                          bodyPid.set(
                              jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                          bodyWritten.countDown();
                          await(releaseBody, "본문 트랜잭션 해제");
                        }));
    CompletableFuture<Void> configuration = null;
    try {
      await(bodyWritten, "본문 쓰기");
      configuration = CompletableFuture.runAsync(context::changeConfiguration);
      PgLocks.awaitBlockedBy(PostgresSupport.dataSource(), bodyPid.get(), WAIT_LIMIT);
      return !configuration.isDone();
    } finally {
      releaseBody.countDown();
      body.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
      if (configuration != null) configuration.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  private void await(CountDownLatch latch, String operation) {
    try {
      if (!latch.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS))
        throw new IllegalStateException(operation + " 대기 상한 초과");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(operation + " 중단", interrupted);
    }
  }
}
