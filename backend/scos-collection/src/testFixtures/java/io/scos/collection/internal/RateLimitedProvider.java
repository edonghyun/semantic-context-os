package io.scos.collection.internal;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.embedding.Embedder;
import io.scos.support.Eventually;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * 첫 호출이 rate_limited(Retry-After 5분, 재시도 예산 600s 안)로 실패해 실제 재시도 대기에 들어가는 공급자(테스트 공용). 대기는 제품과 같다 —
 * 취소 토큰이나 인터럽트로만 깨어난다. 수집 어댑터로도, 임베더로도 붙일 수 있다.
 */
public final class RateLimitedProvider {
  public static final Duration RETRY_AFTER = Duration.ofMinutes(5);

  private final CountDownLatch called = new CountDownLatch(1);
  private final ProviderCallExecutor executor;

  public RateLimitedProvider(Clock clock) {
    this.executor =
        new ProviderCallExecutor(
            ProviderCallPolicy.defaults(() -> 0.0),
            clock,
            (d, c) -> c.awaitCancellation(d),
            TestMetrics.providerCalls());
  }

  /** 수집 런타임에 넘길 실행기(제품과 같은 대기). */
  public ProviderCallExecutor executor() {
    return executor;
  }

  /** 수집이 이 공급자를 부른다(런타임이 준 실행기·토큰으로). */
  public SourceAdapter adapter() {
    return SourceInstances.adapter(input -> call(input.calls(), input.cancel()));
  }

  /** 투영의 임베딩이 이 공급자를 부른다. 임베더 포트는 토큰을 받지 않으므로 취소는 스레드 인터럽트로만 온다. */
  public Embedder embedder(int dim) {
    return new Embedder() {
      @Override
      public int dim() {
        return dim;
      }

      @Override
      public List<double[]> embed(List<String> texts) {
        return call(executor, CancellationToken.none());
      }
    };
  }

  /** 누군가 이 공급자를 호출할 때까지 기다린다. */
  public void awaitCalled(Duration waitLimit) {
    Eventually.await("공급자 호출 시작", () -> called.getCount() == 0, waitLimit);
  }

  private List<double[]> call(CallExecutor calls, CancellationToken cancel) {
    return calls.execute(
        "fake.history",
        timeout -> {
          called.countDown();
          throw new IOException("429");
        },
        e -> CallFailure.rateLimited("slow down", Optional.of(RETRY_AFTER)),
        cancel);
  }
}
