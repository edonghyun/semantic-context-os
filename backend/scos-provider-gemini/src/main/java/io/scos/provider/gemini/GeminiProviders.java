package io.scos.provider.gemini;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.TextGenerator;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini 공급자 팩토리(공개 배포 단계 5 T3, Spring 없음): starter의 빈과 클라이언트 조립이 같은 함수를 부른다. 호출은 받은 정책 위의 실행기로만 한다
 * — 실행기는 빈으로 두지 않는다(수집 런타임의 ProviderCallExecutor 빈과 타입이 겹친다).
 */
public final class GeminiProviders {
  private GeminiProviders() {}

  /** 설정의 모델·차원으로 batchEmbedContents를 부르는 임베더. */
  public static Embedder embedder(
      GeminiSettings settings,
      ProviderCallPolicy policy,
      Clock clock,
      ProviderCallMetrics metrics,
      ObjectMapper json) {
    return new GeminiEmbedder(
        settings.baseUrl(),
        settings.apiKey(),
        settings.model(),
        settings.dim(),
        executor(policy, clock, metrics),
        json);
  }

  /** 받은 모델(재정렬 모델)의 generateContent를 부르는 생성기. 설정의 임베딩 모델·차원은 쓰지 않는다. */
  public static TextGenerator textGenerator(
      GeminiSettings settings,
      String model,
      ProviderCallPolicy policy,
      Clock clock,
      ProviderCallMetrics metrics,
      ObjectMapper json) {
    return new GeminiTextGenerator(
        settings.baseUrl(), settings.apiKey(), model, executor(policy, clock, metrics), json);
  }

  private static ProviderCallExecutor executor(
      ProviderCallPolicy policy, Clock clock, ProviderCallMetrics metrics) {
    return new ProviderCallExecutor(policy, clock, (d, c) -> c.awaitCancellation(d), metrics);
  }
}
