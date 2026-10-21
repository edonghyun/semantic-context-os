package io.scos.connector.notion.internal;

import io.scos.spi.connector.RunErrorKind;

/**
 * TS Notion 어댑터가 일반 `Error`로 던지는 경우(잘못된 체크포인트·바뀐 루트, 요청과 다른 id의 응답, 휴지통 행을 돌려준 data source 조회, 다른
 * 휴지통 단계의 검색 결과, 읽을 수 없는 편집 시각). 공급자 호출 바깥의 검사라 재시도하지 않는다. run에는 TS와 같은 errorKind "Error"를
 * 남기고(CONTRACTS "run errorKind 매핑"), 메시지는 TS 메시지 그대로다.
 */
public final class NotionProtocolException extends RuntimeException implements RunErrorKind {
  /** TS가 일반 Error로 던지는 경우의 run errorKind(TS `error.constructor.name`). */
  public static final String TS_ERROR_KIND = "Error";

  static final String INVALID_CHECKPOINT = "Invalid Notion source checkpoint";
  static final String ROOTS_CHANGED = "Notion source checkpoint does not match configured roots";

  NotionProtocolException(String message) {
    super(message);
  }

  static NotionProtocolException invalidCheckpoint() {
    return new NotionProtocolException(INVALID_CHECKPOINT);
  }

  @Override
  public String runErrorKind() {
    return TS_ERROR_KIND;
  }
}
