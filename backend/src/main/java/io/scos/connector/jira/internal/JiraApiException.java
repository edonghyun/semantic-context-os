package io.scos.connector.jira.internal;

import java.time.Duration;
import java.util.Optional;

/**
 * 2xx가 아닌 Jira 응답(TS JiraApiError). 메시지에는 상태만 싣는다 — 응답 본문·서명 URL·자격 증명은 싣지 않는다. 2xx인데 JSON이 아니면
 * 502로 만든다. retryAfter는 Retry-After 헤더(초 또는 HTTP-date)를 해석한 값이다.
 */
public final class JiraApiException extends RuntimeException {
  private final int status;
  private final Optional<Duration> retryAfter;

  JiraApiException(int status, Optional<Duration> retryAfter) {
    super("Jira request failed (HTTP " + status + ")");
    this.status = status;
    this.retryAfter = retryAfter;
  }

  public int status() {
    return status;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }
}
