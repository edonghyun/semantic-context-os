package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.server.AdminAccessAcceptanceScenario.Bound;
import io.scos.server.AdminAccessAcceptanceScenario.Measurement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("개방 검토는 아홉 측정 항목의 모든 임계 통과를 요구한다(T-c16)")
class AdminAccessAcceptanceResultsTest {
  private final AdminAccessAcceptanceScenario scenario = new AdminAccessAcceptanceScenario();

  @ParameterizedTest
  @ValueSource(strings = {"P-M0", "P-M1", "P-M2", "P-M3", "P-M4", "P-M5", "P-M6", "P-M7", "O-M1"})
  @DisplayName("필수 측정 하나가 없으면 개방 검토를 차단한다")
  void missingMeasurementBlocksOpening(String missing) {
    var measurements = passingMeasurements();
    measurements.remove(missing);
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.openEligible()).isFalse();
    assertThat(result.failedOrMissingIds()).containsExactly(missing);
  }

  @Test
  @DisplayName("제한 p95 비율 1.31은 개방 검토를 차단한다")
  void thresholdViolationBlocksOpening() {
    var measurements = passingMeasurements();
    replace(
        measurements, "P-M4", new Measurement("restrictedP95Ratio", 1.31, 1.3, Bound.LTE, "ratio"));
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.openEligible()).isFalse();
    assertThat(result.failedOrMissingIds()).containsExactly("P-M4");
  }

  @Test
  @DisplayName("필수 아홉 항목의 모든 비교가 통과하면 개방 검토가 가능하다")
  void allRequiredMeasurementsPassAllowsOpeningReview() {
    var measurements = passingMeasurements();
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.openEligible()).isTrue();
    assertThat(result.failedOrMissingIds()).isEmpty();
  }

  @Test
  @DisplayName("빈 측정 항목은 통과로 판정하지 않는다")
  void emptyMeasurementBlocksOpening() {
    var measurements = passingMeasurements();
    measurements.put("P-M5", List.of());
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.failedOrMissingIds()).containsExactly("P-M5");
    assertThat(result.openEligible()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  @DisplayName("유한하지 않은 실측값은 미측정으로 차단한다")
  void nonFiniteActualBlocksOpening(double actual) {
    var measurements = passingMeasurements();
    replace(measurements, "P-M5", new Measurement("status", actual, 1, Bound.LT, "s/source"));
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.openEligible()).isFalse();
    assertThat(result.failedOrMissingIds()).containsExactly("P-M5");
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  @DisplayName("유한하지 않은 임계값도 통과로 판정하지 않는다")
  void nonFiniteLimitBlocksOpening(double limit) {
    var measurements = passingMeasurements();
    replace(measurements, "P-M5", new Measurement("status", 0, limit, Bound.LT, "s/source"));
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.openEligible()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "P-M0,v28,1,1,LT,s,false",
    "P-M0,v28,0.9,1,LT,s,true",
    "P-M4,restrictedP95Ratio,1.3,1.3,LTE,ratio,true",
    "P-M4,restrictedP95Ratio,1.31,1.3,LTE,ratio,false",
    "P-M7,pending,0,0,EQ,count,true",
    "P-M7,pending,1,0,EQ,count,false"
  })
  @DisplayName("고정 임계의 미만·이하·동등 경계값을 구별한다")
  void comparisonHonorsBound(
      String id,
      String metric,
      double actual,
      double limit,
      Bound bound,
      String unit,
      boolean eligible) {
    var measurements = passingMeasurements();
    replace(measurements, id, new Measurement(metric, actual, limit, bound, unit));
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.openEligible()).isEqualTo(eligible);
  }

  @Test
  @DisplayName("같은 항목의 비교 하나라도 실패하면 차단한다")
  void oneFailedComparisonBlocksWholeItem() {
    var measurements = passingMeasurements();
    replace(measurements, "P-M7", new Measurement("pending", 1, 0, Bound.EQ, "count"));
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.openEligible()).isFalse();
    assertThat(result.failedOrMissingIds()).containsExactly("P-M7");
  }

  @Test
  @DisplayName("결과 파일은 미측정과 개방 차단을 명시한다")
  void reportPreservesUnmeasuredThresholds(@TempDir Path directory) throws Exception {
    var output = directory.resolve("acceptance.json");
    // when
    scenario.writeReport(output, Map.of("environment", "synthetic"), Map.of());
    // then
    var report = QueryScenario.JSON.readTree(Files.readString(output));
    assertThat(report.path("openEligible").asBoolean()).isFalse();
    assertThat(report.path("measurements").size()).isEqualTo(9);
    assertThat(report.path("measurements").path("P-M4").get(0))
        .isEqualTo(
            QueryScenario.JSON.readTree(
                "{\"metric\":\"restrictedP95Ratio\",\"actual\":null,\"limit\":1.3,"
                    + "\"bound\":\"LTE\",\"unit\":\"ratio\",\"status\":\"미측정\"}"));
  }

  @Test
  @DisplayName("필수 항목의 비교 일부가 없으면 개방 검토를 차단한다")
  void partialComparisonsBlockOpening() {
    var measurements = passingMeasurements();
    measurements.put("P-M0", List.of(new Measurement("v28", 0.4, 1, Bound.LT, "s")));
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.openEligible()).isFalse();
    assertThat(result.failedOrMissingIds()).containsExactly("P-M0");
  }

  @Test
  @DisplayName("호출자가 완화한 임계는 개방 검토를 차단한다")
  void suppliedLimitCannotRelaxCriterion() {
    var measurements = passingMeasurements();
    replace(
        measurements, "P-M4", new Measurement("restrictedP95Ratio", 1.5, 2, Bound.LTE, "ratio"));
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.openEligible()).isFalse();
    assertThat(result.failedOrMissingIds()).containsExactly("P-M4");
  }

  @ParameterizedTest
  @CsvSource({"2,LTE,ratio", "1.3,LT,ratio", "1.3,LTE,seconds"})
  @DisplayName("고정 임계·비교 방식·단위와 다른 입력은 차단한다")
  void mismatchedCriterionBlocksOpening(double limit, Bound bound, String unit) {
    var measurements = passingMeasurements();
    replace(measurements, "P-M4", new Measurement("restrictedP95Ratio", 1, limit, bound, unit));
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.failedOrMissingIds()).containsExactly("P-M4");
  }

  @Test
  @DisplayName("같은 단위인 비교도 이름으로 대조해 순서와 무관하게 판정한다")
  void metricIdentityPreventsPositionalMatching(@TempDir Path directory) throws Exception {
    var measurements = passingMeasurements();
    measurements.put(
        "P-M1",
        List.of(
            new Measurement("wallRatio", 1.2, 1.2, Bound.LTE, "ratio"),
            new Measurement("commitRatio", 1.1, 1.1, Bound.LTE, "ratio"),
            new Measurement("documentSpacesUpdates", 0, 0, Bound.EQ, "count")));
    var output = directory.resolve("acceptance.json");
    // when
    scenario.writeReport(output, Map.of(), measurements);
    // then
    var report = QueryScenario.JSON.readTree(Files.readString(output));
    assertThat(report.path("openEligible").asBoolean()).isTrue();
    assertThat(report.path("measurements").path("P-M1").get(1).path("actual").asDouble())
        .isEqualTo(1.1);
  }

  @Test
  @DisplayName("보고서도 완화된 임계를 거부하고 고정 임계를 보존한다")
  void reportRejectsMismatchedCriterion(@TempDir Path directory) throws Exception {
    var measurements = passingMeasurements();
    replace(
        measurements, "P-M4", new Measurement("restrictedP95Ratio", 1.5, 2, Bound.LTE, "ratio"));
    var output = directory.resolve("acceptance.json");
    // when
    scenario.writeReport(output, Map.of(), measurements);
    // then
    var report = QueryScenario.JSON.readTree(Files.readString(output));
    assertThat(report.path("openEligible").asBoolean()).isFalse();
    assertThat(report.path("measurements").path("P-M4").get(0).path("status").asString())
        .isEqualTo("FAIL");
    assertThat(report.path("measurements").path("P-M4").get(0).path("limit").asDouble())
        .isEqualTo(1.3);
  }

  @ParameterizedTest
  @ValueSource(strings = {"duplicate", "unknown"})
  @DisplayName("중복되거나 알 수 없는 비교 이름은 개방 검토를 차단한다")
  void invalidMetricBlocksOpening(String metric) {
    var measurements = passingMeasurements();
    var values = new ArrayList<>(measurements.get("P-M4"));
    values.set(
        2,
        new Measurement(
            metric.equals("duplicate") ? "repeatedGinScans" : "unknown", 0, 0, Bound.EQ, "count"));
    measurements.put("P-M4", values);
    // when
    var result = scenario.assess(measurements);
    // then
    assertThat(result.failedOrMissingIds()).containsExactly("P-M4");
  }

  private void replace(Map<String, List<Measurement>> values, String id, Measurement replacement) {
    values.put(
        id,
        values.get(id).stream()
            .map(value -> value.metric().equals(replacement.metric()) ? replacement : value)
            .toList());
  }

  private Map<String, List<Measurement>> passingMeasurements() {
    var values = new HashMap<String, List<Measurement>>();
    migrationAndCollectionMeasurements(values);
    queryAndOperationsMeasurements(values);
    return values;
  }

  private void migrationAndCollectionMeasurements(Map<String, List<Measurement>> values) {
    values.put(
        "P-M0",
        List.of(
            new Measurement("v28", 0.4, 1, Bound.LT, "s"),
            new Measurement("exclusiveLock", 0.2, 1, Bound.LT, "s"),
            new Measurement("v34", 0.3, 1, Bound.LT, "s"),
            new Measurement("v35", 0.5, 1, Bound.LT, "s"),
            new Measurement("previousJarAndR0Evidence", 1, 1, Bound.EQ, "verified")));
    values.put(
        "P-M1",
        List.of(
            new Measurement("documentSpacesUpdates", 0, 0, Bound.EQ, "count"),
            new Measurement("commitRatio", 1.1, 1.1, Bound.LTE, "ratio"),
            new Measurement("wallRatio", 1.2, 1.2, Bound.LTE, "ratio")));
    values.put(
        "P-M2",
        List.of(
            new Measurement("total", 500, 600, Bound.LT, "s"),
            new Measurement("batchTransaction", 1, 2, Bound.LT, "s"),
            new Measurement("postGcHeap", 0.5, 1, Bound.LT, "GB"),
            new Measurement("poolPending", 0, 0, Bound.EQ, "count")));
    values.put(
        "P-M3",
        List.of(
            new Measurement("sweep", 120, 200, Bound.LT, "ms"),
            new Measurement("slackCarrierExpired", 0, 0, Bound.EQ, "count"),
            new Measurement("tickEvidence", 1, 1, Bound.EQ, "verified")));
  }

  private void queryAndOperationsMeasurements(Map<String, List<Measurement>> values) {
    values.put(
        "P-M4",
        List.of(
            new Measurement("restrictedP95Ratio", 1.3, 1.3, Bound.LTE, "ratio"),
            new Measurement("repeatedGinScans", 0, 0, Bound.EQ, "count"),
            new Measurement("qualityAndSubsetEvidence", 1, 1, Bound.EQ, "verified")));
    values.put(
        "P-M5",
        List.of(
            new Measurement("status", 0.02, 1, Bound.LT, "s/source"),
            new Measurement("adminSourceAccess", 0.03, 1, Bound.LT, "s/source"),
            new Measurement("costRunMatches", 1, 1, Bound.EQ, "verified"),
            new Measurement("buffersEvidence", 1, 1, Bound.EQ, "verified")));
    values.put(
        "P-M6",
        List.of(
            new Measurement("rawSemanticUpdates", 0, 0, Bound.EQ, "count"),
            new Measurement("documentAclUpdates", 0, 0, Bound.EQ, "count"),
            new Measurement("projectionRequests", 0, 0, Bound.EQ, "count"),
            new Measurement("costMatchesLog", 1, 1, Bound.EQ, "verified")));
    values.put(
        "P-M7",
        List.of(
            new Measurement("writeActive", 5, 5, Bound.LTE, "count"),
            new Measurement("pending", 0, 0, Bound.EQ, "count"),
            new Measurement("acquisitionFailures5s", 0, 0, Bound.EQ, "count"),
            new Measurement("separateReadPoolEvidence", 1, 1, Bound.EQ, "verified")));
    values.put(
        "O-M1",
        List.of(
            new Measurement("distinctCauses", 3, 3, Bound.EQ, "count"),
            new Measurement("unobservedWarning", 1, 1, Bound.EQ, "verified"),
            new Measurement("safeLogEvidence", 1, 1, Bound.EQ, "verified")));
  }
}
