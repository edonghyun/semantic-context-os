package io.scos.provider.openai;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderJsonHttp;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/**
 * OpenAI 호환의 HTTP 말투: 키가 있을 때만 {@code Authorization: Bearer}를 보낸다(로컬 서버는 키가 없다). 본문 error 코드는
 * code·type 순서로 본다(code가 null이면 type).
 */
final class OpenAiCompatibleHttp {
  private static final List<String> ERROR_CODE_FIELDS = List.of("code", "type");

  private OpenAiCompatibleHttp() {}

  static ProviderJsonHttp over(ProviderCallExecutor executor, Optional<String> apiKey) {
    Map<String, String> headers =
        apiKey
            .filter(k -> !k.isBlank()) // 빈 설정값을 키로 보내지 않는다("Bearer " 헤더 금지)
            .map(k -> Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + k))
            .orElseGet(Map::of);
    return new ProviderJsonHttp(
        executor, new ProviderJsonHttp.Dialect("OpenAI-compatible", headers, ERROR_CODE_FIELDS));
  }

  /** base URL 끝의 '/' 하나를 떼고 path를 붙인다(`/v1`을 덧붙이지 않는다 — 사용자가 준 base URL 그대로). */
  static URI endpoint(String baseUrl, String path) {
    return URI.create(
        (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + path);
  }
}
