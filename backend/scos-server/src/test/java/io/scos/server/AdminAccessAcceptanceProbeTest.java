package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.server.AdminAccessAcceptanceScenario.Bound;
import io.scos.server.AdminAccessAcceptanceScenario.Measurement;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("perf")
@Tag("db")
@Tag("process")
@DisplayName("합성 관리 진단을 측정하고 승인 측정의 빈 항목을 인계한다(T-c16)")
class AdminAccessAcceptanceProbeTest {
  @Test
  @DisplayName("미완료·만료·미연결은 서로 다른 상태·원인 증거를 갖는다")
  void incompleteExpiredAndUnlinkedHaveDistinctEvidence() throws Exception {
    var scenario = new AdminAccessAcceptanceScenario.Diagnostics();
    // when
    var evidence = scenario.diagnose();
    // then
    assertThat(evidence)
        .extracting(AdminAccessAcceptanceScenario.Evidence::summary)
        .containsExactly(
            "incomplete|missing_target|1|0|collection.acl.closed:missing_target",
            "complete|expired|1|1|collection.access.expired:expired",
            "complete|none|0|0|none");
    assertThat(evidence).allMatch(value -> !value.visible());
  }

  @Test
  @DisplayName("측정 결과는 합성 집계만 기록하고 미측정 항목을 보존한다")
  void recordsSyntheticAcceptanceMeasurements() throws Exception {
    var scenario = new AdminAccessAcceptanceScenario.Diagnostics();
    // when
    var measurements = scenario.measure();
    var output = Path.of("build/reports/access-release/admin-acceptance.json");
    new AdminAccessAcceptanceScenario().writeReport(output, scenario.environment(), measurements);
    // then
    // 장비별 지연의 임계 통과는 JUnit에서 단언하지 않는다.
    assertThat(measurements.get("O-M1"))
        .containsExactly(
            new Measurement("distinctCauses", 3, 3, Bound.EQ, "count"),
            new Measurement("unobservedWarning", 1, 1, Bound.EQ, "verified"),
            new Measurement("safeLogEvidence", 1, 1, Bound.EQ, "verified"));
    assertThat(output).isRegularFile();
    assertThat(new AdminAccessAcceptanceScenario().assess(measurements).openEligible()).isFalse();
  }
}
