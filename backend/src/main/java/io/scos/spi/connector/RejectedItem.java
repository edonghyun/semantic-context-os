package io.scos.spi.connector;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * 어댑터가 거른 항목(TS RejectedSourceItem): 원본 저장소에 들어가지 않고 run별 rejection으로 남는다. providerLocator는 JSON.
 */
public record RejectedItem(
    FailureKind failureKind,
    String message,
    Optional<String> providerLocatorJson,
    Instant observedAt,
    Optional<RawContent> quarantined) {
  public enum FailureKind {
    INVALID_ITEM,
    UNSUPPORTED_ITEM;

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static FailureKind fromWire(String s) {
      return valueOf(s.toUpperCase(Locale.ROOT));
    }
  }

  public RejectedItem {
    if (failureKind == null) throw new IllegalArgumentException("failureKind");
    if (message == null) throw new IllegalArgumentException("message");
    if (observedAt == null) throw new IllegalArgumentException("observedAt");
    providerLocatorJson = providerLocatorJson == null ? Optional.empty() : providerLocatorJson;
    quarantined = quarantined == null ? Optional.empty() : quarantined;
  }
}
