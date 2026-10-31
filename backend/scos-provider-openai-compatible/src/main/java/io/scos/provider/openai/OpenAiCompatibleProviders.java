package io.scos.provider.openai;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.TextGenerator;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 호환 공급자 팩토리(공개 배포 단계 5 T3, Spring 없음): starter의 빈과 클라이언트 조립이 같은 함수를 부른다. 필수값 검사는 설정 record가
 * 이미 했다. 호출은 받은 정책 위의 실행기로만 한다.
 */
public final class OpenAiCompatibleProviders {
  private OpenAiCompatibleProviders() {}

  public static Embedder embedder(
      OpenAiCompatibleEmbedderSettings settings,
      ProviderCallPolicy policy,
      Clock clock,
      ProviderCallMetrics metrics,
      ObjectMapper json) {
    return new OpenAiCompatibleEmbedder(
        settings.baseUrl(),
        settings.apiKey(),
        settings.model(),
        settings.dim(),
        executor(policy, clock, metrics),
        json);
  }

  public static TextGenerator textGenerator(
      OpenAiCompatibleGeneratorSettings settings,
      ProviderCallPolicy policy,
      Clock clock,
      ProviderCallMetrics metrics,
      ObjectMapper json) {
    return new OpenAiCompatibleTextGenerator(
        settings.baseUrl(),
        settings.apiKey(),
        settings.model(),
        executor(policy, clock, metrics),
        json);
  }

  private static ProviderCallExecutor executor(
      ProviderCallPolicy policy, Clock clock, ProviderCallMetrics metrics) {
    return new ProviderCallExecutor(policy, clock, (d, c) -> c.awaitCancellation(d), metrics);
  }
}
