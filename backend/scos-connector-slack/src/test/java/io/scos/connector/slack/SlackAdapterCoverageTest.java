package io.scos.connector.slack;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * F5·불변식 6의 어댑터 쪽 조건(TS collectionCoverage): snapshot 모드이고 historySince가 없을 때만 complete_snapshot.
 */
@DisplayName("Slack coverage는 전체 history snapshot만 완전하다(F5·불변식 6)")
class SlackAdapterCoverageTest {
  /** coverage는 공급자를 부르지 않는다 — 닿지 않는 주소. */
  static final String UNREACHABLE = "http://127.0.0.1:1";

  @ParameterizedTest(name = "mode={0}, historySince={1} → {2}")
  @CsvSource({
    "SNAPSHOT, false, COMPLETE_SNAPSHOT",
    "SNAPSHOT, true, INCREMENTAL",
    "INCREMENTAL, false, INCREMENTAL",
    "INCREMENTAL, true, INCREMENTAL"
  })
  @DisplayName("snapshot 모드 + historySince 없음일 때만 complete_snapshot이다")
  void coverage(CollectionMode mode, boolean historySince, CollectionCoverage expected) {
    // given
    SlackAdapter adapter = SlackAssembly.adapter(UNREACHABLE, "t", Clock.systemUTC());
    String config = historySince ? "{\"historySince\":\"2026-01-01T00:00:00Z\"}" : "{}";

    // when
    CollectionCoverage actual =
        adapter.coverage(new SourceConfig(1, config), mode, Optional.empty());

    // then
    assertThat(actual).isEqualTo(expected);
  }
}
