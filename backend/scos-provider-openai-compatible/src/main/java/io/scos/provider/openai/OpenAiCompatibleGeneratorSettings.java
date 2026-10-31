package io.scos.provider.openai;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * OpenAI 호환 생성기(재정렬) 설정(공개 배포 단계 5 T3). base URL·모델은 필수이고, 없으면 그 환경변수 이름으로 거부한다. 빈 API 키는 키가 없는 것으로
 * 본다. timeout은 호출 정책의 시도 timeout 재료다.
 */
public record OpenAiCompatibleGeneratorSettings(
    String baseUrl, String model, Optional<String> apiKey, Duration timeout) {
  public OpenAiCompatibleGeneratorSettings {
    if (baseUrl == null || baseUrl.isBlank())
      throw RequiredSetting.missing("SCOS_RERANK", "BASE_URL");
    if (model == null || model.isBlank()) throw RequiredSetting.missing("SCOS_RERANK", "MODEL");
    apiKey = apiKey.filter(k -> !k.isBlank());
    Objects.requireNonNull(timeout, "timeout");
  }

  /** API 키는 싣지 않고 있는지만 적는다 — 로그·단언 실패 메시지·예외로 새지 않게. */
  @Override
  public String toString() {
    return "OpenAiCompatibleGeneratorSettings[baseUrl="
        + baseUrl
        + ", model="
        + model
        + ", apiKey="
        + (apiKey.isPresent() ? "***" : "<none>")
        + ", timeout="
        + timeout
        + "]";
  }
}
