package io.scos.boot;

import io.scos.provider.gemini.GeminiEmbedder;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 임베더 설정(SCOS_EMBEDDER_*, 공개 배포 단계 5 T3). provider는 gemini|openai-compatible — 기본 gemini(T7에서 바꾼다).
 * gemini면 모델·차원 기본값은 gemini-embedding-001·768이다. openai-compatible의 필수값(base URL·모델·차원)은 공급자 설정
 * record가 환경변수 이름으로 검사한다. timeout은 openai-compatible의 시도 timeout이다(gemini는 scos.gemini.timeout).
 * harness 프로필 밖에서만 바인딩한다.
 */
@Validated
@ConfigurationProperties("scos.embedder")
public record EmbedderProperties(
    @Pattern(regexp = "gemini|openai-compatible") String provider,
    String baseUrl,
    String model,
    Integer dim,
    String apiKey,
    Duration timeout) {
  static final String DEFAULT_PROVIDER = "gemini";
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  public EmbedderProperties {
    provider = provider == null || provider.isBlank() ? DEFAULT_PROVIDER : provider;
    if (provider.equals("gemini")) {
      model = model == null || model.isBlank() ? GeminiEmbedder.DEFAULT_MODEL : model;
      dim = dim == null ? GeminiEmbedder.DEFAULT_DIM : dim;
    }
    timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
  }

  /** API 키는 싣지 않고 있는지만 적는다 — 로그·단언 실패 메시지·예외로 새지 않게. */
  @Override
  public String toString() {
    return "EmbedderProperties[provider="
        + provider
        + ", baseUrl="
        + baseUrl
        + ", model="
        + model
        + ", dim="
        + dim
        + ", apiKey="
        + (apiKey == null ? "<none>" : "***")
        + ", timeout="
        + timeout
        + "]";
  }
}
