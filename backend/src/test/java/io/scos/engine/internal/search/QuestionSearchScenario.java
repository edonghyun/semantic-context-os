package io.scos.engine.internal.search;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.Eventually;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * search_context의 키워드 채널 ∥ 임베딩을 관찰하는 컨텍스트(TESTING §3.5). 키워드 채널은 실제 실행기 스레드에서 돈다. 대역 행동(서로 기다리기,
 * 실패, 임베더 실패 뒤에 끝나는 키워드)과 동기화는 여기서만 만든다.
 */
final class QuestionSearchScenario implements AutoCloseable {
  /** 상대 채널이 시작하기를 기다리는 상한 — 순차 구현이면 이만큼 기다린 뒤 겹치지 않았다고 기록한다. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

  static final SpaceId S = new SpaceId("legacy");
  static final Clock NOW = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);
  static final int DIM = 2;

  private final ExecutorService lexicalExecutor = Executors.newSingleThreadExecutor();
  private final AtomicInteger embedCalls = new AtomicInteger();
  private Executor keywordChannel = lexicalExecutor;
  private final CountDownLatch keywordStarted = new CountDownLatch(1);
  private final CountDownLatch embeddingStarted = new CountDownLatch(1);
  private final CountDownLatch embedderFailed = new CountDownLatch(1);
  private volatile Thread caller;
  private final RuntimeException keywordFailure = new IllegalStateException("키워드 색인 장애");
  private final RuntimeException embedderFailure = new IllegalStateException("임베더 장애");
  private volatile boolean keywordSawEmbedding;
  private volatile boolean embeddingSawKeyword;
  private volatile boolean keywordFinished;
  private Runnable onKeywordSearch = () -> {};
  private Runnable onEmbed = () -> {};

  /** 키워드 검색은 임베딩이 시작되기를, 임베딩은 키워드 검색이 시작되기를 기다린다. */
  void givenChannelsWaitForEachOther() {
    onKeywordSearch =
        () -> {
          keywordStarted.countDown();
          keywordSawEmbedding = await(embeddingStarted, WAIT_LIMIT);
        };
    onEmbed =
        () -> {
          embeddingStarted.countDown();
          embeddingSawKeyword = await(keywordStarted, WAIT_LIMIT);
        };
  }

  /** 키워드 색인이 실패한다. 그 예외를 돌려준다. */
  RuntimeException givenKeywordIndexFails() {
    return givenKeywordIndexThrows(keywordFailure);
  }

  /** 키워드 색인이 주어진 실패(런타임 예외 또는 Error)를 던진다. */
  <T extends Throwable> T givenKeywordIndexThrows(T failure) {
    onKeywordSearch = () -> sneakyThrow(failure);
    return failure;
  }

  /** 임베더가 불린 뒤 키워드 색인이 실패하고, 임베더도 실패한다. 키워드 예외를 돌려준다. */
  RuntimeException givenBothChannelsFail() {
    onKeywordSearch =
        () -> {
          await(embeddingStarted, WAIT_LIMIT);
          throw keywordFailure;
        };
    onEmbed =
        () -> {
          embeddingStarted.countDown();
          throw embedderFailure;
        };
    return keywordFailure;
  }

  /** 키워드 채널을 호출 스레드에서 바로 돌린다 — 임베딩 단계에 닿기 전에 키워드 결과(실패)가 정해진다. */
  void givenKeywordChannelOnCallerThread() {
    keywordChannel = Runnable::run;
  }

  RuntimeException embedderFailure() {
    return embedderFailure;
  }

  /** 임베더가 불린 횟수. */
  int embedCalls() {
    return embedCalls.get();
  }

  /**
   * 임베더가 실패한 뒤, 키워드 검색은 검색 스레드가 검색 안에서 대기에 들어간 것을 본 뒤에야 끝난다 — 시간이 아니라 두 지점(임베더 실패·검색 스레드 대기)이 순서를
   * 정한다. 검색이 키워드를 기다리지 않고 던지면 키워드는 끝나지 않은 채다. 임베더 예외를 돌려준다.
   */
  RuntimeException givenKeywordFinishesOnlyAfterEmbedderFails() {
    onKeywordSearch =
        () -> {
          if (!await(embedderFailed, WAIT_LIMIT))
            throw new IllegalStateException("given 준비 실패: 임베더가 실패하지 않았다");
          Eventually.await("검색 스레드가 검색 안에서 대기", this::callerWaitsInSearch, WAIT_LIMIT);
          keywordFinished = true;
        };
    onEmbed =
        () -> {
          embedderFailed.countDown();
          throw embedderFailure;
        };
    return embedderFailure;
  }

  QuestionSearch.Result search() {
    caller = Thread.currentThread();
    return new QuestionSearch(
            new MemoryUnitStore(),
            new HookedKeywordIndex(),
            new MemoryVectorIndex(),
            new HookedEmbedder(),
            Reranker.DISABLED,
            SearchSettings.DEFAULT,
            NOW,
            TestMetrics.search(),
            keywordChannel)
        .search(AccessScope.singleUser(), S, "정산 배치");
  }

  /** 키워드 검색과 임베딩이 서로가 진행 중인 것을 보았다. */
  boolean channelsOverlapped() {
    return keywordSawEmbedding && embeddingSawKeyword;
  }

  /** 임베더 실패 뒤의 키워드 검색이 끝까지 돌았다. */
  boolean keywordFinished() {
    return keywordFinished;
  }

  @Override
  public void close() {
    lexicalExecutor.shutdownNow();
  }

  private boolean callerWaitsInSearch() {
    Thread t = caller;
    if (t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING)
      return false;
    for (StackTraceElement frame : t.getStackTrace())
      if (frame.getClassName().equals(QuestionSearch.class.getName())) return true;
    return false;
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
    throw (E) failure;
  }

  private static boolean await(CountDownLatch latch, Duration limit) {
    try {
      return latch.await(limit.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("대기 중 인터럽트", e);
    }
  }

  private final class HookedKeywordIndex implements KeywordIndex {
    private final MemoryKeywordIndex delegate = new MemoryKeywordIndex();

    @Override
    public IndexCapabilities capabilities() {
      return delegate.capabilities();
    }

    @Override
    public List<ScoredRef> search(SpaceId space, String text, int k, SearchFilter filter) {
      onKeywordSearch.run();
      return delegate.search(space, text, k, filter);
    }

    @Override
    public void upsert(SpaceId space, List<IndexedText> units) {
      delegate.upsert(space, units);
    }

    @Override
    public void delete(SpaceId space, List<String> unitIds) {
      delegate.delete(space, unitIds);
    }
  }

  private final class HookedEmbedder implements Embedder {
    @Override
    public int dim() {
      return DIM;
    }

    @Override
    public List<double[]> embed(List<String> texts) {
      embedCalls.incrementAndGet();
      onEmbed.run();
      return texts.stream().map(t -> new double[DIM]).toList();
    }
  }
}
