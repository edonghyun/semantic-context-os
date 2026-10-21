package io.scos.connector.tiro;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.DeletionDetection;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 소스 목록의 Tiro 쪽(TS deletionDetection): 완전 스냅샷 수집이라 부재가 삭제 근거다. */
@DisplayName("Tiro 소스 목록은 완전 스냅샷의 부재로 삭제를 안다")
class TiroAdapterListingTest {
  /** 목록은 공급자를 부르지 않는다 — 닿지 않는 주소. */
  static final String UNREACHABLE = "http://127.0.0.1:1";

  @Test
  @DisplayName("삭제는 완전 스냅샷의 부재로 안다")
  void deletionByCompleteSnapshot() {
    // when
    TiroAdapter adapter = TiroAssembly.adapter(UNREACHABLE, "t", Clock.systemUTC());

    // then
    assertThat(adapter.deletionDetection(new SourceConfig(1, "{}")))
        .isEqualTo(DeletionDetection.COMPLETE_SNAPSHOT);
  }
}
