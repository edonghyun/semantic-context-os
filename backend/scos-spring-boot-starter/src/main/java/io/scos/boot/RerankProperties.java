package io.scos.boot;

import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 재정렬 설정(SCOS_RERANK_*). provider는 none|gemini|openai-compatible — 기본 gemini(T7에서 바꾼다). gemini일 때만
 * 모델 기본값 gemini-2.5-flash를 넣는다. openai-compatible의 필수값(base URL·모델)은 공급자 설정 record가 검사한다. timeout은
 * openai-compatible의 시도 timeout이다(gemini는 scos.gemini.timeout). harness도 쓰므로 프로필 밖에서 바인딩한다.
 */
@Validated
@ConfigurationProperties("scos.rerank")
public record RerankProperties(
    @Pattern(regexp = "none|gemini|openai-compatible") String provider,
    String model,
    String baseUrl,
    String apiKey,
    Duration timeout) {
  static final String DEFAULT_PROVIDER = "gemini";
  static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  public RerankProperties {
    provider = provider == null || provider.isBlank() ? DEFAULT_PROVIDER : provider;
    if (provider.equals("gemini"))
      model = model == null || model.isBlank() ? DEFAULT_GEMINI_MODEL : model;
    timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
  }

  public boolean enabled() {
    return !provider.equals("none");
  }

  /** API 키는 싣지 않고 있는지만 적는다 — 로그·단언 실패 메시지·예외로 새지 않게. */
  @Override
  public String toString() {
    return "RerankProperties[provider="
        + provider
        + ", model="
        + model
        + ", baseUrl="
        + baseUrl
        + ", apiKey="
        + (apiKey == null ? "<none>" : "***")
        + ", timeout="
        + timeout
        + "]";
  }
}
