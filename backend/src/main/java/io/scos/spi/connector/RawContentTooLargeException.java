package io.scos.spi.connector;

/**
 * F8: 원본 콘텐츠 50MiB 초과 — 저장·전송 실패와 구분되는 비재시도 오류. run errorKind는 TS 클래스 이름 "RawContentTooLarge"다(TS
 * classifyRunError가 constructor.name을 남긴다). 메시지도 TS와 같다(크기는 싣지 않는다).
 */
public final class RawContentTooLargeException extends RuntimeException implements RunErrorKind {
  static final String TS_ERROR_KIND = "RawContentTooLarge";

  public RawContentTooLargeException(long size, long maxBytes) {
    super(
        "Raw content exceeds " + maxBytes + " byte limit"); // TS RawContentTooLarge 문구(크기는 싣지 않는다)
  }

  @Override
  public String runErrorKind() {
    return TS_ERROR_KIND;
  }
}
