package io.scos.provider.gemini;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderJsonHttp;
import java.util.List;
import java.util.Map;

/** Gemini의 HTTP 말투: 키는 {@code x-goog-api-key} 헤더, 본문 error 코드는 code·type·status 순서로 본다. */
final class GeminiHttp {
  private static final List<String> ERROR_CODE_FIELDS = List.of("code", "type", "status");

  private GeminiHttp() {}

  static ProviderJsonHttp over(ProviderCallExecutor executor, String apiKey) {
    return new ProviderJsonHttp(
        executor,
        new ProviderJsonHttp.Dialect(
            "Gemini", Map.of("x-goog-api-key", apiKey), ERROR_CODE_FIELDS));
  }
}
