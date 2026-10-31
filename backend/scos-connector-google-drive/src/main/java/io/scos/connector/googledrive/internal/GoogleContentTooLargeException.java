package io.scos.connector.googledrive.internal;

import io.scos.spi.connector.RunErrorKind;

/**
 * 응답 본문이 읽기 상한(JSON·다운로드 50 MiB)을 넘었다. 선택 본문이면 수집 제한으로 남고, 필수 응답이면 run이 errorKind
 * GoogleContentTooLarge로 실패한다.
 */
public final class GoogleContentTooLargeException extends RuntimeException implements RunErrorKind {
  static final String TS_ERROR_KIND = "GoogleContentTooLarge";

  GoogleContentTooLargeException() {
    super("Google content exceeds the configured capture limit");
  }

  @Override
  public String runErrorKind() {
    return TS_ERROR_KIND;
  }
}
