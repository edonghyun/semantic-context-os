package io.scos.connector.slack.internal;

import io.scos.spi.connector.CallFailure.Violation;
import java.time.Duration;
import java.util.Optional;

/**
 * Slack 응답 `ok:false`의 error 코드. 응답 형식 위반(invalid_provider_response)이면 어느 검사에서 어긋났는지({@link
 * Violation})를 필드로 든다 — 메시지에는 넣지 않는다(저장된 run의 errorSummary와 같게). 메시지에 토큰·본문을 싣지 않는다.
 */
public final class SlackApiException extends RuntimeException {
  static final String INVALID_PROVIDER_RESPONSE = "invalid_provider_response";

  /** 본문이 JSON 객체가 아니다. */
  static final String BODY_NOT_OBJECT = "body_not_object";

  /** 봉투의 ok가 불리언이 아니다. */
  static final String OK_NOT_BOOLEAN = "ok_not_boolean";

  /** ok:false인데 error가 문자열이 아니다. */
  static final String ERROR_CODE_MISSING = "error_code_missing";

  static final String MESSAGES_NOT_ARRAY = "messages_not_array";
  static final String CHANNELS_NOT_ARRAY = "channels_not_array";

  /** 참여 목록의 채널 항목 하나(객체이고 id가 빈 값 아닌 문자열)가 어긋났다(인덱스와 함께). */
  static final String ITEM_SHAPE = "item_shape";

  static final String RESPONSE_METADATA_NOT_OBJECT = "response_metadata_not_object";
  static final String NEXT_CURSOR_NOT_STRING = "next_cursor_not_string";

  /** 스레드 첫 replies(oldest 없음)가 빈 목록이다 — 부모 메시지조차 없다. */
  static final String THREAD_ROOT_MISSING = "thread_root_missing";

  /** 다음 커서가 그 걷기에서 이미 요청한 커서다(바로 앞 커서 포함). */
  static final String CURSOR_CYCLE = "cursor_cycle";

  private final String code;
  private final Optional<Duration> retryAfter;
  private final Optional<Violation> violation;

  SlackApiException(String code, Optional<Duration> retryAfter) {
    this(code, retryAfter, Optional.empty(), "");
  }

  private SlackApiException(
      String code, Optional<Duration> retryAfter, Optional<Violation> violation, String detail) {
    super("Slack API error: " + code + detail);
    this.code = code;
    this.retryAfter = retryAfter;
    this.violation = violation;
  }

  /** 응답이 이 검사에서 어긋났다: invalid_provider_response. */
  static SlackApiException invalidResponse(String check) {
    return invalidResponse(Violation.of(check));
  }

  static SlackApiException invalidResponse(Violation violation) {
    return new SlackApiException(
        INVALID_PROVIDER_RESPONSE, Optional.empty(), Optional.of(violation), "");
  }

  /** 다음 커서가 이미 요청한 커서로 돌아왔다: invalid_provider_response, 메시지 끝에 걷기 설명(목록·커서 지문)을 괄호로. */
  static SlackApiException cursorCycle(String walk) {
    return new SlackApiException(
        INVALID_PROVIDER_RESPONSE,
        Optional.empty(),
        Optional.of(Violation.of(CURSOR_CYCLE)),
        " (" + walk + ")");
  }

  public String code() {
    return code;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }

  /** invalid_provider_response면 어긋난 검사(알려졌으면). */
  public Optional<Violation> violation() {
    return violation;
  }
}
