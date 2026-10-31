package io.scos.collection.internal;

import java.util.List;

/** 한 소스의 run 상태를 최근 것부터 늘어놓은 이력. 감독 backoff와 `source status`가 같은 실패 셈을 쓴다. */
public record RunHistory(List<RunStatus> newestFirst) {
  public RunHistory {
    newestFirst = List.copyOf(newestFirst);
  }

  public static RunHistory of(List<CollectionRun> runsNewestFirst) {
    return new RunHistory(runsNewestFirst.stream().map(CollectionRun::status).toList());
  }

  /** 마지막 성공 뒤의 실패 수 — 최근 run부터 거슬러 올라가 첫 성공에서 멈춘다. */
  public int failuresSinceLastSuccess() {
    int count = 0;
    for (RunStatus status : newestFirst) {
      if (status.isSuccess()) break;
      if (status.countsAsFailure()) count++;
    }
    return count;
  }
}
