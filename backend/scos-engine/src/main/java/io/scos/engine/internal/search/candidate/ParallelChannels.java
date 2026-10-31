package io.scos.engine.internal.search.candidate;

import io.scos.spi.store.ScoredRef;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 후보 두 채널을 함께 돌린다: 키워드 채널은 전용 실행기에서, 임베딩 → 벡터는 호출 스레드에서. 두 쪽이 다 끝난 뒤 돌려주므로 순서·동점은 순차 실행과 같고, 호출자가
 * 보는 실패도 순차 실행과 같다(키워드 예외가 이긴다).
 */
final class ParallelChannels {
  /** 질의 텍스트마다 채널의 결과 목록. */
  record Hits(List<List<ScoredRef>> lexical, List<List<ScoredRef>> vector) {}

  private final Executor lexicalExecutor;

  ParallelChannels(Executor lexicalExecutor) {
    this.lexicalExecutor = lexicalExecutor;
  }

  Hits run(
      Supplier<List<List<ScoredRef>>> lexicalChannel,
      Supplier<List<List<ScoredRef>>> vectorChannel) {
    CompletableFuture<List<List<ScoredRef>>> lexicalJob =
        CompletableFuture.supplyAsync(lexicalChannel, lexicalExecutor);
    List<List<ScoredRef>> vector;
    try {
      throwIfFailed(lexicalJob); // 순차처럼: 키워드가 이미 실패했으면 임베더(유료 API)를 부르지 않는다
      vector = vectorChannel.get();
    } catch (RuntimeException | Error failure) {
      throw unchecked(keywordFailureFirst(lexicalJob, failure));
    }
    return new Hits(join(lexicalJob), vector);
  }

  /**
   * 키워드 채널의 결과. 실패는 감싸지 않고 원래 예외로 던진다. 한쪽만 실패하면 호출자가 보는 예외는 순차 실행과 같고, 둘 다 실패해도 순차처럼 키워드 예외가
   * 이긴다({@link #keywordFailureFirst}).
   */
  private static <T> T join(CompletableFuture<T> job) {
    try {
      return job.join();
    } catch (CompletionException e) {
      throw unchecked(e.getCause() == null ? e : e.getCause());
    }
  }

  private static void throwIfFailed(CompletableFuture<?> job) {
    if (job.isCompletedExceptionally()) join(job);
  }

  /**
   * 임베딩·벡터 쪽이 실패하면 키워드 채널이 끝나기를 기다린 뒤 던질 예외를 고른다 — 응답 뒤에 이 요청의 질의가 연결을 쥐고 남지 않는다. 키워드도 실패했으면 순차
   * 실행처럼 키워드 예외가 이기고 임베딩·벡터 예외는 덧붙인다(suppressed). 이미 진행 중인 임베더 호출은 취소하지 못한다(Embedder 포트에 취소가 없다) —
   * 그 호출이 끝날 때까지 실패 응답이 늦는다.
   */
  @SuppressWarnings("ReferenceEquality") // 같은 예외 객체인지 본다: throwIfFailed가 다시 던진 키워드 예외
  private static Throwable keywordFailureFirst(CompletableFuture<?> job, Throwable failure) {
    Throwable keyword =
        job.handle(
                (ignored, error) ->
                    error instanceof CompletionException c && c.getCause() != null
                        ? c.getCause()
                        : error)
            .join();
    if (keyword == null || keyword == failure) return failure;
    keyword.addSuppressed(failure);
    return keyword;
  }

  /** 런타임 예외는 그대로 돌려주고 Error는 그대로 던진다. 그 밖(포트는 checked 예외를 선언하지 않는다)은 감싼다. */
  private static RuntimeException unchecked(Throwable t) {
    if (t instanceof Error e) throw e;
    return t instanceof RuntimeException r ? r : new CompletionException(t);
  }
}
