package io.scos.spi.connector;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/** 분류된 수집 실패(TS CallFailure, F7·H1): 종류가 재시도 가능 여부를 정한다. rate_limited만 retryAfter를 가질 수 있다. */
public record CallFailure(Kind kind, String message, Optional<Duration> retryAfter) {
  public enum Kind {
    INVALID_CONFIGURATION(false),
    INVALID_CHECKPOINT(false),
    AUTHENTICATION_FAILED(false),
    PERMISSION_DENIED(false),
    RATE_LIMITED(true),
    DEPENDENCY_UNAVAILABLE(true),
    INVALID_PROVIDER_RESPONSE(false),
    PAYLOAD_TOO_LARGE(false),
    STORAGE_FAILED(true),
    CHECKPOINT_CONFLICT(true),
    CANCELLED(false);

    private final boolean retryable;

    Kind(boolean retryable) {
      this.retryable = retryable;
    }

    public boolean retryable() {
      return retryable;
    }

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public CallFailure {
    if (kind == null) throw new IllegalArgumentException("kind");
    message = message == null ? "" : message;
    retryAfter = retryAfter == null ? Optional.empty() : retryAfter;
    if (retryAfter.isPresent() && kind != Kind.RATE_LIMITED)
      throw new IllegalArgumentException("retryAfter only for rate_limited");
  }

  public static CallFailure of(Kind kind, String message) {
    return new CallFailure(kind, message, Optional.empty());
  }

  public static CallFailure rateLimited(String message, Optional<Duration> retryAfter) {
    return new CallFailure(Kind.RATE_LIMITED, message, retryAfter);
  }

  public boolean retryable() {
    return kind.retryable();
  }
}
