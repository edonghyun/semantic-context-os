package io.scos.collection.internal.postgres;

import io.scos.collection.internal.CollectionRuntime;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 다른 스레드에서 도는 수집 하나와 그 결과 받기(커넥터 종류와 무관한 DB 종단 테스트 공용). 대기 상한은 hang 방지용이다. */
public final class BackgroundCollection {
  public static final Duration WAIT_LIMIT = Duration.ofSeconds(60);

  /** 수집마다 가상 스레드 — 게이트에 막힌 수집이 공용 ForkJoinPool을 점유해 다른 비동기 작업을 굶기지 않게(F4 리뷰 A-M11). */
  private static final ExecutorService THREADS = Executors.newVirtualThreadPerTaskExecutor();

  private BackgroundCollection() {}

  public static Future<CollectionRuntime.Report> start(
      CollectionRuntime runtime,
      SourceInstanceId source,
      CollectionMode mode,
      CancellationToken cancel) {
    return CompletableFuture.supplyAsync(() -> runtime.collect(source, mode, cancel), THREADS);
  }

  public static CollectionRuntime.Report finish(Future<CollectionRuntime.Report> run)
      throws Exception {
    return run.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
  }

  /** 수집이 던진 예외(정상 종료면 전제 실패). */
  public static Throwable failureOf(Future<CollectionRuntime.Report> run)
      throws InterruptedException {
    try {
      run.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (TimeoutException e) {
      throw new IllegalStateException("수집이 끝나지 않았다", e);
    }
    throw new IllegalStateException("given 준비 실패: 수집이 실패해야 한다");
  }
}
