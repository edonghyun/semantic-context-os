package io.scos.connector.notion.internal;

import java.time.Duration;
import java.util.Optional;

/**
 * Notion API 오류(TS NotionApiError): Notion 오류 코드(`unauthorized`·`object_not_found`·`rate_limited`… 응답 본문의
 * code, 문자열이 아니면 `http_error`)와 Retry-After. 응답이 JSON 객체가 아니거나 형식이 틀리면 `invalid_provider_response`,
 * 불완전한 목록이면 `incomplete_search_results`·`incomplete_query_results`다. 메시지는 TS와 같은 "Notion API error:
 * <code>"이고 응답 본문·토큰은 싣지 않는다.
 */
public final class NotionApiException extends RuntimeException {
  static final String INVALID_PROVIDER_RESPONSE = "invalid_provider_response";

  private final String code;
  private final Optional<Duration> retryAfter;

  NotionApiException(String code, Optional<Duration> retryAfter) {
    super("Notion API error: " + code);
    this.code = code;
    this.retryAfter = retryAfter;
  }

  static NotionApiException invalidResponse() {
    return new NotionApiException(INVALID_PROVIDER_RESPONSE, Optional.empty());
  }

  public String code() {
    return code;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }
}
