package io.scos.collection.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 최근 run부터 읽은 접근 이력. 비용은 현재 결과 run에만 속하며 본문 성공·실행 중·미시도는 접근 완료와 구분한다. */
public record AccessHistory(
    Optional<AccessResult> result,
    Optional<Instant> finishedAt,
    boolean running,
    int consecutiveIncomplete,
    Optional<Instant> lastCompleteAt,
    Set<String> unreadableFiles,
    Optional<SourceQueries.RunCost> cost) {
  public AccessHistory {
    unreadableFiles = Set.copyOf(unreadableFiles);
  }

  public static AccessHistory of(List<CollectionRun> newestFirst) {
    var latest = newestFirst.stream().findFirst();
    int incomplete = 0;
    Optional<Instant> complete = Optional.empty();
    for (var run : newestFirst) {
      if (run.accessFinishedAt().isEmpty()) continue;
      if (run.accessResult() == AccessResult.COMPLETE) {
        complete = run.accessFinishedAt();
        break;
      }
      if (run.accessResult() == AccessResult.INCOMPLETE
          || run.accessResult() == AccessResult.CLAIM_LOST) incomplete++;
    }
    return new AccessHistory(
        latest.map(CollectionRun::accessResult),
        latest.flatMap(CollectionRun::accessFinishedAt),
        latest.map(run -> run.status() == RunStatus.RUNNING).orElse(false),
        incomplete,
        complete,
        newestFirst.stream()
            .filter(run -> run.accessFinishedAt().isPresent())
            .findFirst()
            .map(CollectionRun::aclUnreadable)
            .orElse(Set.of()),
        latest.flatMap(
            run ->
                run.accessCost()
                    .map(
                        cost ->
                            new SourceQueries.RunCost(run.id(), cost.recordedAt(), cost.cost()))));
  }
}
