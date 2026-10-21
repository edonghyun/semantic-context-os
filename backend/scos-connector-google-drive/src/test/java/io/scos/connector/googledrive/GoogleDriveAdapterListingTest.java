package io.scos.connector.googledrive;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.DeletionDetection;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 소스 목록의 Drive 쪽(TS deletionDetection): 삭제·접근 상실은 변경 피드가 알린다 — 목록의 부재는 근거가 아니다. */
@DisplayName("Drive 소스 목록은 삭제를 변경 피드로 안다(TS deletionDetection)")
class GoogleDriveAdapterListingTest {
  /** 목록은 공급자를 부르지 않는다 — 닿지 않는 주소. */
  static final String UNREACHABLE = "http://127.0.0.1:1";

  @Test
  @DisplayName("삭제는 공급자 변경 피드로 안다")
  void deletionByProviderEvents() {
    // when
    GoogleDriveAdapter adapter =
        GoogleDriveAssembly.adapter(
            UNREACHABLE, GoogleDriveAssembly.CREDENTIAL_JSON, Clock.systemUTC());

    // then
    assertThat(adapter.deletionDetection(new SourceConfig(1, "{}")))
        .isEqualTo(DeletionDetection.PROVIDER_EVENTS);
  }
}
