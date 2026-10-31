package io.scos.provider.gemini;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Gemini 접속·임베딩 설정(공개 배포 단계 5 T3). API 키는 필수다. base URL을 비우면 공식 엔드포인트, 모델·차원을 비우면
 * gemini-embedding-001·768, timeout을 비우면 30초다. timeout은 호출 정책({@code ProviderCallPolicies.of})의 시도
 * timeout 재료다.
 */
public record GeminiSettings(
    String apiKey, String baseUrl, String model, Integer dim, Duration timeout) {
  public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** 비운 값(null·공백)은 위 기본값이다. API 키가 없으면 {@link IllegalArgumentException}. */
  public GeminiSettings(
      @Nullable String apiKey,
      @Nullable String baseUrl,
      @Nullable String model,
      @Nullable Integer dim,
      @Nullable Duration timeout) {
    if (apiKey == null || apiKey.isBlank())
      throw new IllegalArgumentException("GEMINI_API_KEY is required for the gemini provider");
    this.apiKey = apiKey;
    this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    this.model = model == null || model.isBlank() ? GeminiEmbedder.DEFAULT_MODEL : model;
    this.dim = dim == null ? GeminiEmbedder.DEFAULT_DIM : dim;
    this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
  }

  /** API 키는 싣지 않는다 — 로그·단언 실패 메시지·예외로 새지 않게. */
  @Override
  public String toString() {
    return "GeminiSettings[apiKey=***, baseUrl="
        + baseUrl
        + ", model="
        + model
        + ", dim="
        + dim
        + ", timeout="
        + timeout
        + "]";
  }
}
