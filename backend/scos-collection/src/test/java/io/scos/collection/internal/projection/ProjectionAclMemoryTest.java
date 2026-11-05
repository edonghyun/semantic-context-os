package io.scos.collection.internal.projection;

import static io.scos.collection.internal.projection.ProjectionAclMemoryScenario.DOC;
import static io.scos.collection.internal.projection.ProjectionAclMemoryScenario.READER;
import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.scos.engine.internal.projection.DocumentIngestor;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import io.scos.support.LogCapture;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@DisplayName("메모리 투영은 ACL과 점유 및 재시도 계약을 지킨다")
class ProjectionAclMemoryTest {
  final ProjectionAclMemoryScenario scenario = new ProjectionAclMemoryScenario();

  @BeforeEach
  void givenPreviouslyAllowedDocument() {
    scenario.givenProjectedDocument();
  }

  @Test
  @DisplayName("접근 수집이 꺼져도 충돌 수를 소스 상태에 저장한다")
  void ownershipCollisionWithoutAccessIsStored() {
    scenario.foreignSourceCollision();
    scenario.withoutAccessCollection();
    scenario.project();
    // then
    assertThat(scenario.ownershipSkippedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("sync 뒤 점유 상실은 충돌 진단 수치도 저장하지 못한다")
  void claimLossAfterSyncCannotStoreOwnershipCount() {
    scenario.foreignSourceCollision();
    scenario.loseClaimAfterSync();
    scenario.project();
    // then
    assertThat(scenario.ownershipSkippedCount()).isZero();
  }

  @Test
  @DisplayName("진단 수치 저장 뒤 점유를 잃어도 충돌 원본을 닫지 못한다")
  void claimLossAfterOwnershipReportCannotRestrictCarrier() {
    scenario.foreignSourceCollision();
    scenario.loseClaimAfterOwnershipReport();
    scenario.project();
    // then
    assertThat(scenario.foreignAcl().status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("본문 실패 뒤 마감 직전 취소도 CANCELLED로 보고한다")
  void cancellationAfterBodyFailureIsCancelled() {
    scenario.cancelAfterFailedSync();
    var report = scenario.project();
    // then
    assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.CANCELLED);
  }

  @Test
  @DisplayName("렌더 실패로 ACL을 닫으면 원인과 문서 지문을 기록한다")
  void renderingFailureLogsClosureCause() {
    scenario.renderFails();
    try (var logs = LogCapture.of(DocumentIngestor.class)) {
      scenario.project();
      // then
      assertThat(logs.events("projection.access.closed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("detail").path("reason").asString())
                      .isEqualTo("render_failed"));
    }
  }

  @Test
  @DisplayName("소유 충돌 수를 투영 결과에 남긴다")
  void ownershipCollisionIsReported() {
    scenario.foreignSourceCollision();
    var report = scenario.project();
    // then
    assertThat(report).extracting("ownershipSkipped").isEqualTo(1);
  }

  @Test
  @DisplayName("접근 수집이 꺼져도 소유 충돌 경고를 남긴다")
  void ownershipCollisionWithoutAccessLogsWarning() {
    scenario.foreignSourceCollision();
    scenario.withoutAccessCollection();
    try (var log = LogCapture.of(ProjectionRun.class)) {
      scenario.project();
      // then
      assertThat(log.events("projection.document.ownership_skipped"))
          .singleElement()
          .satisfies(event -> assertThat(event.path("detail").path("count").asInt()).isEqualTo(1));
    }
  }

  @Test
  @DisplayName("carrier 묶음 전체를 빠뜨려도 해당 문서는 FAILED로 남는다")
  void wholeBundleOmissionClosesAcl() {
    scenario.omitWholeBundle();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl()).extracting(DocumentAcl::status).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("carrier 묶음 전체 누락은 요청을 재시도로 남긴다")
  void wholeBundleOmissionKeepsRequestForRetry() {
    scenario.omitWholeBundle();
    // when
    scenario.project();
    // then
    assertThat(scenario.request()).isPresent();
  }

  @Test
  @DisplayName("입력 carrier 변형을 거부한 투영은 요청을 재시도로 남긴다")
  void consumingInputKeepsProjectionForRetry() {
    scenario.consumeRestrictingCarrier();
    // when
    scenario.project();
    // then
    assertThat(scenario.request()).isPresent();
  }

  @Test
  @DisplayName("투영기가 입력에서 제한 carrier를 지워도 검증 기준을 바꾸지 못한다")
  void consumingInputCannotHideRestrictingCarrier() {
    scenario.consumeRestrictingCarrier();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("제한 carrier가 묶음에서 빠지면 해당 문서는 FAILED다")
  void missingRestrictingCarrierClosesAcl() {
    scenario.omitRestrictingCarrier();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("투영 출력 documentId가 묶음과 다르면 해당 문서는 FAILED다")
  void mismatchedRenderedDocumentClosesAcl() {
    scenario.changeRenderedDocumentId();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("투영 출력 sourceId가 원천과 다르면 해당 문서는 FAILED다")
  void mismatchedRenderedSourceClosesAcl() {
    scenario.changeRenderedSourceId();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("저장 장애로 실패한 투영은 재시도 실패를 기록한다")
  void writeFailureRecordsRetry() {
    scenario.writeFailsWithMessage();
    scenario.project();
    // then
    assertThat(scenario.request().orElseThrow().attempts()).isEqualTo(1);
  }

  @Test
  @DisplayName("렌더 중 종료되면 ACL 쓰기를 시작하지 않고 CANCELLED다")
  void cancelledBeforeAclLoopIsReported() {
    scenario.cancelDuringRendering();
    // when
    var report = scenario.project();
    // then
    assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.CANCELLED);
  }

  @Test
  @DisplayName("렌더 중 종료되면 기존 ACL 결정 시각을 보존한다")
  void cancelledBeforeAclLoopWritesNothing() {
    scenario.cancelDuringRendering();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl())
        .isEqualTo(new DocumentAcl(DOC, Status.OK, Set.of(READER), MemoryProjection.T0));
  }

  @Test
  @DisplayName("렌더 예외는 기존 OK ACL을 FAILED로 대체한다")
  void renderFailureClosesAcl() {
    scenario.renderFails();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl())
        .isEqualTo(
            new DocumentAcl(
                DOC,
                Status.FAILED,
                Set.of(),
                MemoryProjection.T0.plusNanos(1000),
                AccessFailureReason.RENDER_FAILED));
  }

  @Test
  @DisplayName("렌더 empty는 세대 검사로 prune이 막혀도 ACL을 먼저 닫는다")
  void emptyRenderClosesAclBeforeBlockedPrune() {
    scenario.renderIsEmptyWithBlockedPrune();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("그룹 묶기 실패는 이전 소유 문서의 ACL을 FAILED로 닫는다")
  void groupingFailureClosesPreviouslyOwnedAcl() {
    scenario.groupingFails();
    // when
    scenario.project();
    // then
    assertThat(scenario.acl().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("사라진 문서는 prune이 막혀도 ACL을 먼저 닫는다")
  void vanishedDocumentClosesAclBeforePrune() {
    scenario.vanishedDocumentWithBlockedPrune();
    scenario.project();
    // then
    assertThat(scenario.acl().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("정상 재투영은 살아 있는 문서의 허용 ACL을 유지한다")
  void liveDocumentKeepsAllowedAcl() {
    scenario.project();
    // then
    assertThat(scenario.acl())
        .isEqualTo(new DocumentAcl(DOC, Status.OK, Set.of(READER), MemoryProjection.T0));
  }

  @Test
  @DisplayName("점유가 만료된 투영은 실패로 보고하고 재시도를 기록한다")
  void expiredClaimRecordsFailure() {
    scenario.claimExpiresDuringRendering();
    var report = scenario.project();
    // then
    assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.FAILED);
    assertThat(scenario.request().orElseThrow().attempts()).isEqualTo(1);
  }

  @Test
  @DisplayName("후속 작업자가 점유하면 SUPERSEDED로 보고한다")
  void replacedClaimIsSuperseded() {
    scenario.claimIsReplacedDuringRendering();
    var report = scenario.project();
    // then
    assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUPERSEDED);
  }

  @Test
  @DisplayName("종료와 함께 점유를 잃으면 CANCELLED로 보고한다")
  void claimLossDuringCancellationIsCancelled() {
    scenario.cancelAndLoseClaimAtGuard();
    var report = scenario.project();
    // then
    assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.CANCELLED);
  }

  @Test
  @DisplayName("heartbeat 저장 실패가 남으면 ACL 쓰기를 거부한다")
  void heartbeatFailurePreventsAclWrite() {
    scenario.heartbeatFailsDuringRendering();
    var report = scenario.project();
    // then
    assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.FAILED);
    assertThat(scenario.acl())
        .isEqualTo(new DocumentAcl(DOC, Status.OK, Set.of(READER), MemoryProjection.T0));
  }

  @Test
  @DisplayName("메시지가 있는 저장 장애는 보고에 원인을 남긴다")
  void writeFailureReportsMessage() {
    scenario.writeFailsWithMessage();
    var report = scenario.project();
    // then
    assertThat(report.error()).contains("injected write failure");
  }

  @Test
  @DisplayName("메시지 없는 저장 장애는 보고에 예외 종류를 남긴다")
  void writeFailureReportsTypeWithoutMessage() {
    scenario.writeFailsWithoutMessage();
    var report = scenario.project();
    // then
    assertThat(report.error()).contains("IllegalStateException");
  }

  @Test
  @DisplayName("실행 중인 투영 점유가 있으면 중복 시작을 건너뛴다")
  void runningProjectionIsSkipped() {
    scenario.projectionAlreadyRunning();
    var report = scenario.project();
    // then
    assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SKIPPED_RUNNING);
  }

  @Test
  @DisplayName("설정이 사라진 소스의 요청도 재시도 실패를 기록한다")
  void missingSourceRecordsFailure() {
    scenario.missingSource();
    scenario.project();
    // then
    assertThat(scenario.request().orElseThrow().attempts()).isEqualTo(1);
  }

  @Test
  @DisplayName("다른 출처의 소유 충돌 운반 원본을 원래 관측 시각에서 닫는다")
  void collisionRestrictsRawCarrier() {
    scenario.foreignSourceCollision();
    scenario.project();
    // then
    assertThat(scenario.foreignAcl())
        .isEqualTo(RawAcl.failed(AccessFailureReason.OWNERSHIP_CONFLICT, MemoryProjection.T0));
  }

  @Test
  @DisplayName("소유 충돌의 raw ACL 변경은 후속 투영을 요청한다")
  void collisionRequestsFollowupProjection() {
    scenario.foreignSourceCollision();
    scenario.project();
    // then
    assertThat(scenario.request().orElseThrow().version()).isEqualTo(2);
  }

  @Test
  @DisplayName("이미 차단된 충돌의 후속 투영은 요청을 끝낸다")
  void collisionFollowupDoesNotLoop() {
    scenario.foreignSourceCollision();
    scenario.project();
    // when
    scenario.project();
    // then
    assertThat(scenario.request()).isEmpty();
  }

  @Test
  @DisplayName("sync 뒤 점유를 잃으면 충돌 원본 ACL을 저장하지 않는다")
  void claimLossAfterSyncCannotRestrictForeignCarrier() {
    scenario.foreignSourceCollision();
    scenario.loseClaimAfterSync();
    scenario.project();
    // then
    assertThat(scenario.foreignAcl().status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("empty 렌더의 건너뛴 문서 수를 양수로 보고한다")
  void emptyRenderCountsOneSkippedDocument() {
    scenario.renderIsEmpty();
    var report = scenario.project();
    // then
    assertThat(report.projectionSkipped()).isEqualTo(1);
  }

  @Test
  @DisplayName("투영 종료는 heartbeat 예약을 해제한다")
  void terminalProjectionStopsHeartbeat() {
    scenario.project();
    // then
    assertThat(scenario.projection.scheduler.liveTasks()).isZero();
  }

  @Test
  @DisplayName("새 임베딩 입력 수를 투영 지표에 연결한다")
  void embeddingInputsAreReported() {
    scenario.changeBody();
    scenario.project();
    // then
    assertThatMetrics(scenario.registry)
        .hasCounter(ProjectionMetrics.EMBEDDING_INPUTS, "source", "embedder", 1);
  }

  @Test
  @DisplayName("성공한 문서 수를 투영 지표에 연결한다")
  void successfulDocumentsAreReported() {
    scenario.project();
    // then
    assertThatMetrics(scenario.registry)
        .hasCounter(ProjectionMetrics.DOCUMENTS, Tags.of("outcome", "ok", "reason", "none"), 1);
  }

  @Test
  @DisplayName("투영 타이머는 호출 전체에서 측정한 경과 시간을 넘지 않는다")
  void runTimerMeasuresElapsedTime() {
    var timing = scenario.measuredProject();
    // then
    assertThat(timing.recorded()).isBetween(0.0, (double) timing.enclosing());
  }

  @Test
  @DisplayName("연속 실패가 설정한 세 번째에 이르면 오류 이벤트를 남긴다")
  void repeatedFailureLogsAtThreshold() {
    scenario.repeatedFailures(2);
    try (var log = LogCapture.of(ProjectionRun.class)) {
      scenario.project();
      // then
      assertThat(log.events("projection.request.failed_repeatedly"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("detail").path("consecutiveFailures").asInt())
                      .isEqualTo(3));
    }
  }

  @Test
  @DisplayName("연속 실패가 설정한 세 번째에 이르기 전에는 오류 이벤트를 내지 않는다")
  void repeatedFailureDoesNotLogBeforeThreshold() {
    scenario.repeatedFailures(1);
    try (var log = LogCapture.of(ProjectionRun.class)) {
      scenario.project();
      // then
      assertThat(log.events("projection.request.failed_repeatedly")).isEmpty();
    }
  }
}
