package io.scos.client;

import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.client.collect.CollectLock;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** 준비 실패 전 실제 잠금과 막힌 수집을 확보하고, 실패 후 남은 자원을 관찰한다. */
final class ScosStdioPreparationScenario implements AutoCloseable {
  private final ScosLocalScenario local;
  private final ScosStdioSession session;
  private boolean interruptedAfterPreparation;

  ScosStdioPreparationScenario(Path root) throws IOException {
    local = new ScosLocalScenario(root);
    local.givenProject();
    session = local.startBlockedStdio();
  }

  private ScosStdioPreparationScenario(ScosLocalScenario local, ScosStdioSession session) {
    this.local = local;
    this.session = session;
  }

  static ScosStdioPreparationScenario failedEnvironmentRead(Path root) {
    ScosLocalScenario local = new ScosLocalScenario(root);
    Map<String, String> environment = local.environment();
    Map<String, String> failingEnvironment =
        new AbstractMap<>() {
          @Override
          public String get(Object key) {
            if ("SCOS_HOME".equals(key))
              throw new IllegalStateException("injected environment read failure: SCOS_HOME");
            return environment.get(key);
          }

          @Override
          public Set<Entry<String, String>> entrySet() {
            return environment.entrySet();
          }
        };
    return new ScosStdioPreparationScenario(
        local, new ScosStdioSession(failingEnvironment, UnaryOperator.identity(), () -> {}));
  }

  Throwable failPreparation(Throwable failure) {
    return catchThrowable(
        () ->
            session.prepare(
                () -> {
                  if (failure instanceof RuntimeException exception) throw exception;
                  throw (Error) failure;
                }));
  }

  Throwable interruptPreparation(Throwable failure) {
    try {
      Thread.currentThread().interrupt();
      return failPreparation(failure);
    } finally {
      // 이 시나리오가 만든 인터럽트가 다음 테스트로 넘어가지 않게 한다.
      interruptedAfterPreparation = Thread.interrupted();
    }
  }

  boolean interruptedAfterPreparation() {
    return interruptedAfterPreparation;
  }

  boolean lockAvailable() {
    var lock = CollectLock.tryAcquire(local.state().resolveSibling("collect.lock"));
    lock.ifPresent(CollectLock::close);
    return lock.isPresent();
  }

  boolean runnerTerminated() {
    return session.terminated();
  }

  @Override
  public void close() throws Exception {
    try {
      if (!session.terminated()) session.close();
    } finally {
      local.close();
    }
  }
}
