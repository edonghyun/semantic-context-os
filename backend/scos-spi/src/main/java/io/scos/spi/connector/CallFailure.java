package io.scos.spi.connector;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * 분류된 수집 실패(F7·H1): 종류가 재시도 가능 여부를 정한다. rate_limited만 retryAfter를 가질 수 있다.
 * invalid_provider_response만 어느 응답 검사가 실패했는지({@link Violation})를 가질 수 있다 — 실패 이벤트에 싣고 메시지에는 넣지 않는다.
 */
public record CallFailure(
    Kind kind, String message, Optional<Duration> retryAfter, Optional<Violation> violation) {
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

  /**
   * 공급자 응답이 어느 검사에서 어긋났는가: 검사 이름(커넥터가 정하는 snake_case, 예 `results_not_array`)과 항목 검사면 그 항목의 인덱스. 응답
   * 본문·값은 싣지 않는다.
   */
  public record Violation(String check, OptionalInt itemIndex) {
    public Violation {
      if (check == null || check.isEmpty()) throw new IllegalArgumentException("check");
      itemIndex = itemIndex == null ? OptionalInt.empty() : itemIndex;
    }

    public static Violation of(String check) {
      return new Violation(check, OptionalInt.empty());
    }

    public static Violation item(String check, int index) {
      return new Violation(check, OptionalInt.of(index));
    }
  }

  /** message가 null이면 빈 문자열, retryAfter·violation이 null이면 empty다. */
  public CallFailure(
      Kind kind,
      @Nullable String message,
      Optional<Duration> retryAfter,
      Optional<Violation> violation) {
    if (kind == null) throw new IllegalArgumentException("kind");
    Optional<Duration> wait = retryAfter == null ? Optional.empty() : retryAfter;
    if (wait.isPresent() && kind != Kind.RATE_LIMITED)
      throw new IllegalArgumentException("retryAfter only for rate_limited");
    Optional<Violation> failed = violation == null ? Optional.empty() : violation;
    if (failed.isPresent() && kind != Kind.INVALID_PROVIDER_RESPONSE)
      throw new IllegalArgumentException("violation only for invalid_provider_response");
    this.kind = kind;
    this.message = message == null ? "" : message;
    this.retryAfter = wait;
    this.violation = failed;
  }

  public CallFailure(Kind kind, @Nullable String message, Optional<Duration> retryAfter) {
    this(kind, message, retryAfter, Optional.empty());
  }

  public static CallFailure of(Kind kind, @Nullable String message) {
    return new CallFailure(kind, message, Optional.empty());
  }

  public static CallFailure rateLimited(@Nullable String message, Optional<Duration> retryAfter) {
    return new CallFailure(Kind.RATE_LIMITED, message, retryAfter);
  }

  /** invalid_provider_response, 어긋난 검사가 알려졌으면 그것과 함께. */
  public static CallFailure invalidResponse(
      @Nullable String message, Optional<Violation> violation) {
    return new CallFailure(Kind.INVALID_PROVIDER_RESPONSE, message, Optional.empty(), violation);
  }

  public boolean retryable() {
    return kind.retryable();
  }
}
