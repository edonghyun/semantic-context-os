package io.scos.collection.internal;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Isolated
@DisplayName("run 완료 뒤 재조정 원장에서 보고를 복원한다")
class ReconciliationReportingTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String COMPLETED_EVENT =
      """
      {"level":"info","event":"collection.reconciliation.completed",
       "sourceInstanceId":"reconciliation-source","runId":"reconciliation-run",
       "detail":{"eligiblePages":1,"missedPages":1,"missRatio":1,"partial":true,
                 "discoveries":["Count[kind=PAGE, classification=INCREMENTAL_MISS, states=1]"],
                 "lastSuccessAgeSeconds":"N/A"}}
      """;
  static final String FAILED_EVENT =
      """
      {"level":"warn","event":"collection.reconciliation.report_failed",
       "sourceInstanceId":"reconciliation-source","runId":"reconciliation-run"}
      """;
  static final long MISSING_AGE = -1;
  final ReconciliationReportingScenario context = new ReconciliationReportingScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  @DisplayName("재조정 완료는 info에 소스·run과 원장 집계를 남긴다")
  void logsCompletedReconciliation() {
    context.collect();
    // then
    assertThat(context.log.events("collection.reconciliation.completed"))
        .singleElement()
        .isEqualTo(JSON.readTree(COMPLETED_EVENT));
  }

  @Test
  @DisplayName("보고 실패는 warn에 소스·run과 원인을 남긴다")
  void logsReportingFailure() {
    context.reportingBackendFails();
    context.collect();
    // then
    assertThat(context.log.events("collection.reconciliation.report_failed"))
        .singleElement()
        .extracting(
            line -> line.at("/error/type").asString(),
            line -> line.at("/error/message").asString(),
            line -> ((ObjectNode) line).without("error"))
        .containsExactly(
            IllegalStateException.class.getName(),
            ReconciliationReportingScenario.REPORT_FAILURE_MESSAGE,
            JSON.readTree(FAILED_EVENT));
  }

  @Test
  @DisplayName("전체 정리 이력이 없으면 성공 age를 0으로 꾸미지 않는다")
  void missingCleanupHistoryHasUnavailableAge() {
    // when
    context.collect();
    // then
    assertThat(context.reportDetail().path("lastSuccessAgeSeconds").asString()).isEqualTo("N/A");
  }

  @Test
  @DisplayName("데이터 커밋 뒤 실패한 run도 원장의 partial 표본을 보고한다")
  void reportsFailedRunAsPartial() {
    // given
    context.failAfterPage = true;
    // when
    context.collect();
    // then
    assertThat(context.reportDetail().path("partial").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("재조정 관측이 없는 run은 재조정 보고를 만들지 않는다")
  void ordinaryRunHasNoReconciliationReport() {
    // given
    context.emitPage = false;
    // when
    context.collect();
    // then
    assertThat(context.log.events("collection.reconciliation.completed")).isEmpty();
  }

  @Test
  @DisplayName("완료 뒤 지표 보고 실패는 마감한 run을 다시 실패로 마감하지 않는다")
  void reportingFailureDoesNotRefinalizeRun() {
    // given
    context.reportingBackendFails();
    // when·then
    assertThatCode(context::collect).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("전체 성공 age는 실제 정리 완료 이력에서 계산한다")
  void reportsAgeFromActualCleanup() {
    // given
    context.cleanupCompletedEarlier();
    // when
    context.collect();
    // then
    assertThat(context.reportDetail().path("lastSuccessAgeSeconds").asLong(MISSING_AGE))
        .isEqualTo(ReconciliationReportingScenario.CLEANUP_AGE_SECONDS);
  }

  @Test
  @DisplayName("분모 없는 원장 보고의 누락률은 구조화 로그에서도 N/A다")
  void structuredReportKeepsRatioUnavailable() {
    // given
    context.bootstrap = true;
    // when
    context.collect();
    // then
    assertThat(context.reportDetail().path("missRatio").asString()).isEqualTo("N/A");
  }

  @Test
  @DisplayName("실제 커밋된 페이지 상태의 누락 수를 run 완료 뒤 지표에 반영한다")
  void runtimeReportsCommittedDiscovery() {
    // when
    context.collect();
    // then
    assertThatMetrics(context.registry)
        .hasCounter(
            CollectionMetrics.RECONCILIATION_DISCOVERED, ReconciliationMetricsTest.PARTIAL_MISS, 1);
  }
}
