package io.scos.connector.notion.internal;

import io.scos.spi.connector.RunErrorKind;

/**
 * Notion 어댑터의 프로토콜 위반(잘못된 체크포인트·바뀐 루트, 요청과 다른 id의 응답, 휴지통 행을 돌려준 data source 조회, 다른 휴지통 단계의 검색 결과,
 * 읽을 수 없는 편집 시각). 공급자 호출 바깥의 검사라 재시도하지 않는다. run에는 저장된 run과 같은 errorKind "Error"를 남기고(CONTRACTS "run
 * errorKind 매핑"), 메시지도 저장값과 같게 둔다.
 */
public final class NotionProtocolException extends RuntimeException implements RunErrorKind {
  /** 프로토콜 위반의 run errorKind. 저장된 run 값이라 바꾸지 않는다. */
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
