package io.scos.connector.googledrive.internal;

import java.time.Duration;
import java.util.Optional;

/**
 * 2xx가 아닌 Google 응답, 또는 클라이언트가 공급자 고장으로 보는 응답(TS GoogleApiError): 상태, 허용 목록의 이유(`rate_limited`·
 * `invalid_json`·`missing_body`·`invalid_oauth_response`·`incomplete_download`), Retry-After. 메시지는
 * 상태와 이유만 싣는다 — 응답 본문·토큰·자격 증명은 싣지 않는다.
 */
public final class GoogleApiException extends RuntimeException {
  /** 오류 메시지에 싣는 허용 목록의 이유(TS 문자열 그대로). */
  enum Reason {
    RATE_LIMITED("rate_limited"),
    INVALID_JSON("invalid_json"),
    MISSING_BODY("missing_body"),
    INVALID_OAUTH_RESPONSE("invalid_oauth_response"),
    INCOMPLETE_DOWNLOAD("incomplete_download");

    private final String wire;

    Reason(String wire) {
      this.wire = wire;
    }
  }

  private final int status;
  private final Optional<Reason> reason;
  private final Optional<Duration> retryAfter;

  GoogleApiException(int status, Optional<Reason> reason, Optional<Duration> retryAfter) {
    super(
        "Google API request failed (HTTP "
            + status
            + reason.map(r -> ", " + r.wire).orElse("")
            + ")");
    this.status = status;
    this.reason = reason;
    this.retryAfter = retryAfter;
  }

  /** 공급자 고장(502 등)과 그 이유. */
  static GoogleApiException of(int status, Reason reason) {
    return new GoogleApiException(status, Optional.of(reason), Optional.empty());
  }

  public int status() {
    return status;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }

  boolean rateLimited() {
    return reason.filter(Reason.RATE_LIMITED::equals).isPresent();
  }
}
