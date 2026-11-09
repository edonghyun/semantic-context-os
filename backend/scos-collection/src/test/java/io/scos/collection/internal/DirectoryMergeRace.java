package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.postgres.DirectoryMergePause;
import io.scos.spi.source.SourceIdentityRecord;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

/** 실제 병합을 멈춘 동안 관리 조회가 기다리는지 확인하고 모든 작업을 회수한다. */
final class DirectoryMergeRace implements AutoCloseable {
  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final Duration BLOCKED = Duration.ofMillis(200);
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final CountDownLatch held = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch following = new CountDownLatch(1);
  private final SourceRegistrationAdminScenario scenario;
  private final Optional<DirectoryMergePause> databasePause;

  DirectoryMergeRace(SourceRegistrationAdminScenario scenario, Optional<DataSource> database) {
    this.scenario = scenario;
    databasePause = database.map(DirectoryMergePause::new);
  }

  Future<AccessMerge> holdMerge() {
    if (databasePause.isPresent()) {
      var merge = workers.submit(() -> scenario.merge(() -> {}));
      databasePause.get().awaitHeld();
      return merge;
    }
    var merge =
        workers.submit(
            () ->
                scenario.merge(
                    () -> {
                      held.countDown();
                      await(release);
                    }));
    await(held);
    return merge;
  }

  Future<List<SourceIdentityRecord>> readLockedSource() {
    return workers.submit(() -> scenario.readLockedSource(following::countDown));
  }

  void requireWaiting(Future<?> result) {
    await(following);
    assertThatThrownBy(() -> result.get(BLOCKED.toMillis(), TimeUnit.MILLISECONDS))
        .isInstanceOf(TimeoutException.class);
  }

  void release() {
    release.countDown();
    databasePause.ifPresent(DirectoryMergePause::release);
  }

  private void await(CountDownLatch gate) {
    try {
      if (!gate.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
        throw new IllegalStateException("Directory merge gate timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @Override
  public void close() {
    release();
    workers.close();
    databasePause.ifPresent(DirectoryMergePause::close);
  }
}
