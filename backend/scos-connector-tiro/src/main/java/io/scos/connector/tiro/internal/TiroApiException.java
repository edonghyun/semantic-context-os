package io.scos.connector.tiro.internal;

import java.time.Duration;
import java.util.Optional;

/**
 * Tiro 응답 실패(TS TiroApiError): 2xx가 아닌 상태, 또는 2xx인데 본문이 JSON이 아님(502로 기록). 메시지에 응답 본문·서명 URL·키를 싣지
 * 않는다. retryAfter는 Retry-After 헤더(초 또는 HTTP-date)를 읽은 값이다.
 */
public final class TiroApiException extends RuntimeException {
  /** 2xx 본문이 JSON이 아닐 때 기록하는 상태(TS와 같다). */
  static final int NON_JSON_BODY = 502;

  private final int status;
  private final Optional<Duration> retryAfter;

  public TiroApiException(int status, Optional<Duration> retryAfter) {
    super("Tiro request failed (HTTP " + status + ")");
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
