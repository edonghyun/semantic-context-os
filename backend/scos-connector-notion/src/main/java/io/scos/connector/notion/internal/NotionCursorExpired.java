package io.scos.connector.notion.internal;

/** 커서를 가진 요청만 같은 고정 하한에서 재시작할 수 있다는 호출 결과. */
final class NotionCursorExpired extends RuntimeException {
  NotionCursorExpired() {
    super("Notion pagination cursor is invalid");
  }
}
