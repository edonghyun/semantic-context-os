package io.scos.connector.googledrive.internal;

/**
 * 이 본문·범위·파일은 이 계정·API로 읽을 수 없다(TS UnavailableContent) — 수집 제한(거부·limitations)으로 남기고 계속한다. 메시지는 거부와
 * limitations에 그대로 저장된다(TS 문구).
 */
class UnavailableContent extends RuntimeException {
  UnavailableContent(String message) {
    super(message);
  }

  /** 범위를 읽는 사이 시트 버전이 바뀌었다(TS SpreadsheetVersionChanged) — 같은 버전의 남은 범위도 버린다. */
  static final class SpreadsheetVersionChanged extends UnavailableContent {
    SpreadsheetVersionChanged(String message) {
      super(message);
    }
  }
}
