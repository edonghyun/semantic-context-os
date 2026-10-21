package io.scos.connector.slack.internal;

import java.time.Duration;
import java.util.Optional;

/** Slack 응답 `ok:false`의 error 코드(TS SlackWebApiError). 메시지에 토큰·본문을 싣지 않는다. */
public final class SlackApiException extends RuntimeException {
  private final String code;
  private final Optional<Duration> retryAfter;

  SlackApiException(String code, Optional<Duration> retryAfter) {
    super("Slack API error: " + code);
    this.code = code;
    this.retryAfter = retryAfter;
  }

  public String code() {
    return code;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }
}
