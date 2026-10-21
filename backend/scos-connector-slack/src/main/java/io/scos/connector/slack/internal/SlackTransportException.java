package io.scos.connector.slack.internal;

/**
 * 전송 층 실패(비-JSON·빈 본문 — LB의 502/503 HTML 등). TS에서는 `response.json()`의 SyntaxError = 분류
 * 기본값(dependency_unavailable, 재시도).
 */
final class SlackTransportException extends RuntimeException {
  SlackTransportException(int status, String detail) {
    super("Slack transport failure: HTTP " + status + " " + detail);
  }
}
