package io.scos.connector.notion.internal;

import io.scos.spi.connector.CallFailure.Violation;
import java.time.Duration;
import java.util.Optional;

/**
 * Notion API 오류: Notion 오류 코드(`unauthorized`·`object_not_found`·`rate_limited`… 응답 본문의
 * code, 문자열이 아니면 `http_error`)와 Retry-After. 응답이 JSON 객체가 아니거나 형식이 틀리면 `invalid_provider_response`이고
 * 어느 검사에서 어긋났는지({@link Violation})를 필드로 든다. 불완전한 목록이면 `incomplete_search_results`·
 * `incomplete_query_results`다. 메시지는 "Notion API error: <code>"이고(커서 순환이면 뒤에 걷기 설명) 응답 본문·토큰은 싣지 않는다.
 * 검사 이름은 메시지에 넣지 않는다 — 저장된 run의 errorSummary와 같게 둔다.
 */
public final class NotionApiException extends RuntimeException {
  static final String INVALID_PROVIDER_RESPONSE = "invalid_provider_response";

  /** 본문을 JSON으로 읽을 수 없다(빈 본문·HTML 포함). 상태와 무관하다. */
  static final String NON_JSON_BODY = "non_json_body";

  /** 2xx 본문이 JSON 객체가 아니다. */
  static final String BODY_NOT_OBJECT = "body_not_object";

  /** 단건 조회의 객체 모양(object·id·시각·in_trash 등)이 어긋났다. */
  static final String OBJECT_SHAPE = "object_shape";

  static final String LIST_OBJECT = "list_object";
  static final String LIST_TYPE = "list_type";
  static final String HAS_MORE_NOT_BOOLEAN = "has_more_not_boolean";
  static final String RESULTS_NOT_ARRAY = "results_not_array";

  /** 목록 항목 하나의 모양이 어긋났다(인덱스와 함께). */
  static final String ITEM_SHAPE = "item_shape";

  /** has_more가 true인데 next_cursor가 빈 값이 아닌 문자열이 아니다. */
  static final String HAS_MORE_WITHOUT_NEXT_CURSOR = "has_more_without_next_cursor";

  /** has_more가 false인데 next_cursor가 null이 아니다(필드가 없는 것도 null이 아니다). */
  static final String NEXT_CURSOR_ON_LAST_PAGE = "next_cursor_on_last_page";

  /** 다음 커서가 그 걷기에서 이미 요청한 커서로 돌아왔다. */
  static final String CURSOR_CYCLE = "cursor_cycle";

  private final boolean invalidCursor;
  private final String code;
  private final Optional<Duration> retryAfter;
  private final Optional<Violation> violation;

  NotionApiException(String code, Optional<Duration> retryAfter) {
    this(code, retryAfter, false);
  }

  NotionApiException(String code, Optional<Duration> retryAfter, boolean invalidCursor) {
    this(code, retryAfter, invalidCursor, Optional.empty(), "");
  }

  private NotionApiException(
      String code,
      Optional<Duration> retryAfter,
      boolean invalidCursor,
      Optional<Violation> violation,
      String detail) {
    super("Notion API error: " + code + detail);
    this.invalidCursor = invalidCursor;
    this.code = code;
    this.retryAfter = retryAfter;
    this.violation = violation;
  }

  /** 응답이 이 검사에서 어긋났다: invalid_provider_response. */
  static NotionApiException invalidResponse(String check) {
    return invalidResponse(Violation.of(check));
  }

  static NotionApiException invalidResponse(Violation violation) {
    return new NotionApiException(
        INVALID_PROVIDER_RESPONSE, Optional.empty(), false, Optional.of(violation), "");
  }

  boolean invalidCursor() {
    return invalidCursor;
  }

  /** 다음 커서가 이미 요청한 커서로 돌아왔다: invalid_provider_response, 메시지 끝에 걷기 설명(목록·커서 지문)을 괄호로. */
  static NotionApiException cursorCycle(String walk) {
    return new NotionApiException(
        INVALID_PROVIDER_RESPONSE,
        Optional.empty(),
        false,
        Optional.of(Violation.of(CURSOR_CYCLE)),
        " (" + walk + ")");
  }

  public String code() {
    return code;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }

  /** invalid_provider_response면 어긋난 검사. */
  public Optional<Violation> violation() {
    return violation;
  }
}
