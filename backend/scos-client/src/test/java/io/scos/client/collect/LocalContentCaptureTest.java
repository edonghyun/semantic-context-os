package io.scos.client.collect;

import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.ContentCaptureContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("로컬 캡처는 콘텐츠 캡처 계약을 지킨다(E2)")
class LocalContentCaptureTest extends ContentCaptureContract {
  @Override
  protected ContentCapture newCapture() {
    return new LocalContentCapture();
  }
}
