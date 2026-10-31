package io.scos.provider.openai;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * OpenAI 호환 임베더 설정(공개 배포 단계 5 T3). base URL·모델·차원은 필수이고, 없으면 그 환경변수 이름으로 거부한다. 빈 API 키는 키가 없는 것으로
 * 본다(인증 헤더를 보내지 않는다). timeout은 호출 정책의 시도 timeout 재료다.
 */
public record OpenAiCompatibleEmbedderSettings(
    String baseUrl, String model, Integer dim, Optional<String> apiKey, Duration timeout) {
  public OpenAiCompatibleEmbedderSettings {
    if (baseUrl == null || baseUrl.isBlank())
      throw RequiredSetting.missing("SCOS_EMBEDDER", "BASE_URL");
    if (model == null || model.isBlank()) throw RequiredSetting.missing("SCOS_EMBEDDER", "MODEL");
    if (dim == null) throw RequiredSetting.missing("SCOS_EMBEDDER", "DIM");
    apiKey = apiKey.filter(k -> !k.isBlank());
    Objects.requireNonNull(timeout, "timeout");
  }

  /** API 키는 싣지 않고 있는지만 적는다 — 로그·단언 실패 메시지·예외로 새지 않게. */
  @Override
  public String toString() {
    return "OpenAiCompatibleEmbedderSettings[baseUrl="
        + baseUrl
        + ", model="
        + model
        + ", dim="
        + dim
        + ", apiKey="
        + (apiKey.isPresent() ? "***" : "<none>")
        + ", timeout="
        + timeout
        + "]";
  }
}
