package io.scos.boot;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 재정렬 설정(TS SCOS_RERANK_PROVIDER·SCOS_RERANK_MODEL). provider는 none|gemini만 — 다른 공급자는 P4 이후. */
@Validated
@ConfigurationProperties("scos.rerank")
public record RerankProperties(@Pattern(regexp = "none|gemini") String provider, String model) {
  public RerankProperties {
    provider = provider == null || provider.isBlank() ? "gemini" : provider;
    model = model == null || model.isBlank() ? "gemini-2.5-flash" : model;
  }

  public boolean enabled() {
    return !provider.equals("none");
  }
}
