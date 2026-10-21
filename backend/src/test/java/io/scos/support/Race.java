package io.scos.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * "동시에 시작"하는 경합(TESTING §7): 모든 참가자가 출발선(준비 gate)에 선 뒤 한꺼번에 출발(출발 gate)하고, 결과를 참가자 순서대로 모은다. 대기 상한은
 * hang 방지용이며 넘으면 예외다. 이것도 DB 안의 겹침은 보장하지 않는다 — 결정적 경합은 서버 측 대기(PgLocks)로.
 */
public final class Race {
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

  private Race() {}

  public static <T> List<T> run(int participants, IntFunction<T> attempt) throws Exception {
    CountDownLatch ready = new CountDownLatch(participants);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(participants);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < participants; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!go.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS))
                    throw new IllegalStateException("출발 gate 시간 초과");
                  return attempt.apply(index);
                }));
      }
      if (!ready.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: 모든 참가자가 출발선에 서지 못했다");
      go.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> f : futures) results.add(f.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS));
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
