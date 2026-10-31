package io.scos.collection.internal;

import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.ContentCaptureContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("수집 런타임 캡처는 콘텐츠 캡처 계약을 지킨다(E2)")
class RawContentCaptureContractTest extends ContentCaptureContract {
  @Override
  protected ContentCapture newCapture() {
    return RawContentCapture.defaults();
  }
}
