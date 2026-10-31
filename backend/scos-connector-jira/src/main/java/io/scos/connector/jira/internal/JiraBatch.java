package io.scos.connector.jira.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.RawMutation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 배치 실행: 작업들을 동시에(가상 스레드) 돌리고 모두 끝난 뒤 배치 순서대로 결과를 돌려준다(하나뿐이면 호출 스레드에서). 작업 하나의 실패는 그 칸의 결과로 남는다 —
 * 무엇을 확정할지는 호출자가 정한다.
 */
final class JiraBatch {
  /** 작업 하나의 결과: 원본 변경과 큐 앞에 넣을 다음 작업. */
  record Collected(List<RawMutation> mutations, List<JiraWork> next) {}

  /** 배치 안 작업 하나의 끝: 결과 또는 실패. */
  sealed interface Outcome {
    record Done(JiraWork work, Collected collected) implements Outcome {}

    record Failed(JiraWork work, RuntimeException failure) implements Outcome {}
  }

  @FunctionalInterface
  interface Task {
    Collected collect(JiraWork work);
  }

  private final Task task;

  JiraBatch(Task task) {
    this.task = task;
  }

  List<Outcome> run(List<JiraWork> batch) {
    if (batch.size() == 1) return List.of(attempt(batch.getFirst()));
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Outcome>> futures =
          batch.stream().map(work -> threads.submit(() -> attempt(work))).toList();
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> future : futures) outcomes.add(await(future));
      return outcomes;
    }
  }

  private static Outcome await(Future<Outcome> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CallFailureException(
          "jira.collect", CallFailure.of(CallFailure.Kind.CANCELLED, "interrupted"));
    } catch (ExecutionException e) {
      throw new IllegalStateException("Jira task did not record its outcome", e.getCause());
    }
  }

  private Outcome attempt(JiraWork work) {
    try {
      return new Outcome.Done(work, task.collect(work));
    } catch (RuntimeException e) {
      return new Outcome.Failed(work, e);
    }
  }
}
