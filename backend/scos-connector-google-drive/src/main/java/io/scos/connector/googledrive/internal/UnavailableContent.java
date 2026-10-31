package io.scos.connector.googledrive.internal;

/**
 * 이 본문·범위·파일은 이 계정·API로 읽을 수 없다 — 수집 제한(거부·limitations)으로 남기고 계속한다. 메시지는 거부와 limitations에 그대로 저장되므로
 * 문구를 바꾸지 않는다.
 */
class UnavailableContent extends RuntimeException {
  private final String reason;

  UnavailableContent(String message) {
    super(message);
    this.reason = message;
  }

  /** 생성자가 받은 메시지({@link #getMessage()}와 같다 — null이 아니다). */
  String reason() {
    return reason;
  }

  /** 범위를 읽는 사이 시트 버전이 바뀌었다 —같은 버전의 남은 범위도 버린다. */
  static final class SpreadsheetVersionChanged extends UnavailableContent {
    SpreadsheetVersionChanged(String message) {
      super(message);
    }
  }
}
