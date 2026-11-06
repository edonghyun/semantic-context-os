package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.RawAcl;
import io.scos.support.LogCapture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("접근 단계는 active carrier를 보충하고 독립 결과를 낸다(§5.2)")
// 공유 AccessCollection 로거의 다른 테스트 로그가 섞이지 않도록 격리한다.
@Isolated
class AccessCollectionTest {
  AccessCollectionScenario scenario = new AccessCollectionScenario("slack");

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("대상 누락을 FAILED로 닫으면 원인을 기록한다")
  void missingTargetLogsClosureCause() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    try (var logs = LogCapture.of(AclCommit.class)) {
      scenario.collect();
      // then
      assertThat(logs.events("collection.acl.closed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("detail").path("reason").asString())
                      .isEqualTo("missing_target"));
    }
  }

  @Test
  @DisplayName("확정 독자 거부 파일은 접근 저장과 함께 run 결과에 보존한다")
  void unreadableAclIdsAreCommittedWithPage() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    scenario.pages(
        new AclPage(
            Map.of(
                "file:F1", RawAcl.observed(Status.FAILED, Set.of(), AccessCollectionScenario.T1)),
            List.of(),
            List.of(),
            Set.of("file:F1")));
    scenario.collect();
    // then
    assertThat(scenario.unreadableAclIds()).containsExactly("file:F1");
  }

  @Test
  @DisplayName("관측 읽기도 실패할 때 선행 점유 상실을 먼저 보고한다")
  void lostClaimPrecedesObservationReadFailure() {
    scenario.lostClaimBeforeObservationFailure();
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.CLAIM_LOST);
  }

  @Test
  @DisplayName("선정 직전 점유를 잃으면 원본 읽기 장애보다 점유 상실을 보고한다")
  void lostClaimPrecedesRawReadFailure() {
    scenario.lostClaimBeforeRawReadFailure();
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.CLAIM_LOST);
  }

  @Test
  @DisplayName("원본 읽기 뒤 점유를 잃으면 로컬 결정 장애보다 점유 상실을 보고한다")
  void lostClaimPrecedesResolutionFailure() {
    scenario.active("C1:1700000000.000001", "slack.message");
    scenario.lostClaimAfterRawReadBeforeResolutionFailure();
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.CLAIM_LOST);
  }

  @Test
  @DisplayName("반복 예산 실패 뒤에는 아직 관측하지 않은 대상을 먼저 시도한다")
  void persistentBudgetFailureRefreshesTailFirst() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:A", "gdrive.file");
    scenario.active("file:B", "gdrive.file");
    scenario.budgetForFirstTarget();
    scenario.collect();
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("file:A", "file:B", "file:B", "file:A");
  }

  @Test
  @DisplayName("접근 대상은 오래된 관측 순이며 동률은 식별자 순이다")
  void targetsUseOldestObservationThenId() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:A", "gdrive.file");
    scenario.active("file:B", "gdrive.file");
    scenario.active("file:C", "gdrive.file");
    scenario.withAcl("file:A", Status.OK);
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("file:B", "file:C", "file:A");
  }

  @Test
  @DisplayName("첫 보충은 삭제된 원본과 조각을 제외한 모든 carrier를 고른다")
  void firstBackfillIncludesAllActiveCarriers() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    scenario.active("file:F2", "gdrive.file");
    scenario.active("drive:D1", "gdrive.drive");
    scenario.active("file:deleted", "gdrive.file");
    scenario.deleted("file:deleted");
    scenario.active("sheet:S1", "gdrive.sheet");
    scenario.active("block:B1", "notion.block");
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactlyInAnyOrder("file:F1", "file:F2", "drive:D1");
  }

  @Test
  @DisplayName("Drive 재수집은 변경 없는 OK 파일도 포함한다")
  void driveRefreshIncludesPreviouslyOkFiles() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    scenario.withAcl("file:F1", Status.OK);
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("file:F1");
  }

  @Test
  @DisplayName("어댑터가 선언한 재수집 종류는 원천 이름에 관계없이 OK도 선정한다")
  void adapterDeclaresRefreshKinds() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("custom-refresh");
    scenario.active("file:F1", "gdrive.file");
    scenario.withAcl("file:F1", Status.OK);
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("file:F1");
  }

  @Test
  @DisplayName("Jira 재수집은 모든 문서 종류를 포함하고 조각은 제외한다")
  void jiraRefreshIncludesAllDocumentKinds() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("jira");
    scenario.active("issue:I1", "jira.issue");
    scenario.active("project:P1", "jira.project");
    scenario.active("component:C1", "jira.component");
    scenario.active("version:V1", "jira.version");
    scenario.active("filter:F1", "jira.filter");
    scenario.active("dashboard:D1", "jira.dashboard");
    scenario.active("board:B1", "jira.board");
    scenario.active("sprint:S1", "jira.sprint");
    scenario.active("field:F2", "jira.field");
    scenario.active("attachment:A1", "jira.attachment");
    scenario.withAcl("issue:I1", Status.OK);
    scenario.withAcl("project:P1", Status.OK);
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets())
        .containsExactlyInAnyOrder(
            "issue:I1",
            "project:P1",
            "component:C1",
            "version:V1",
            "filter:F1",
            "dashboard:D1",
            "board:B1",
            "sprint:S1");
  }

  @Test
  @DisplayName("Slack 과거 메시지 세 개를 본문 API 없이 보충한다")
  void slackBackfillUsesNoMessageApi() {
    // given
    scenario.active("C1:1700000000.000001", "slack.message");
    scenario.active("C1:1700000000.000002", "slack.message");
    scenario.active("C1:1700000000.000003", "slack.message");
    scenario.pages(
        new AclPage(
            Map.of(
                "C1:1700000000.000001",
                    RawAcl.observed(
                        Status.OK,
                        Set.of(AccessKey.parse("group:access-source:C1")),
                        AccessCollectionScenario.T1),
                "C1:1700000000.000002",
                    RawAcl.observed(
                        Status.OK,
                        Set.of(AccessKey.parse("group:access-source:C1")),
                        AccessCollectionScenario.T1),
                "C1:1700000000.000003",
                    RawAcl.observed(
                        Status.OK,
                        Set.of(AccessKey.parse("group:access-source:C1")),
                        AccessCollectionScenario.T1)),
            List.of(),
            List.of()));
    // when
    scenario.collect();
    // then
    assertThat(
            scenario.acls("C1:1700000000.000001", "C1:1700000000.000002", "C1:1700000000.000003"))
        .containsOnlyKeys("C1:1700000000.000001", "C1:1700000000.000002", "C1:1700000000.000003")
        .allSatisfy(
            (id, acl) ->
                assertThat(acl.keys()).containsExactly(AccessKey.parse("group:access-source:C1")));
    assertThat(scenario.targets())
        .containsExactly("C1:1700000000.000001", "C1:1700000000.000002", "C1:1700000000.000003");
  }

  @Test
  @DisplayName("Notion은 대상이나 접근 호출 없이 UNSUPPORTED다")
  void unsupportedSourceHasNoRawTargets() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("notion");
    scenario.active("page:P1", "notion.page");
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.UNSUPPORTED);
    assertThat(scenario.accessCalls()).isZero();
    assertThat(scenario.acls("page:P1")).isEmpty();
  }

  @Test
  @DisplayName("미구현 지원 어댑터는 INCOMPLETE다")
  void unimplementedSupportedAdapterIsIncomplete() {
    // given
    scenario.unimplementedAccess();
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.INCOMPLETE);
  }

  @Test
  @DisplayName("미구현 지원 어댑터는 HTTP 실패가 아닌 미구현 예외를 보고한다")
  void unimplementedFailureIsNotAProviderFailure() {
    scenario.unimplementedAccess();
    try (var log = LogCapture.of(AccessCollection.class)) {
      scenario.collect();
      // then
      assertThat(log.events("collection.access.incomplete"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("error").path("type").asString())
                      .isEqualTo("java.lang.UnsupportedOperationException"));
    }
  }

  @Test
  @DisplayName("접근 실패는 소스·run과 예외 원인을 경고 이벤트로 남긴다")
  void accessFailureLogsCause() {
    // given
    scenario.failAfterPages();
    try (var log = LogCapture.of(AccessCollection.class)) {
      // when
      scenario.collect();
      // then
      assertThat(log.events("collection.access.incomplete"))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(
                        List.of(
                            event.path("level").asString(),
                            event.path("sourceInstanceId").asString(),
                            event.path("runId").asString(),
                            event.path("error").path("type").asString(),
                            event.path("error").path("message").asString()))
                    .containsExactly(
                        "warn",
                        "access-source",
                        "access-run",
                        "java.lang.IllegalStateException",
                        "provider failed after page");
              });
    }
  }

  @Test
  @DisplayName("접근 전 취소는 어댑터를 부르지 않고 INCOMPLETE다")
  void cancelledBeforeAccessDoesNotCallAdapter() {
    // given
    scenario.cancel.cancel();
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.accessCalls()).isZero();
  }

  @Test
  @DisplayName("수집을 끄면 ACL이나 관측을 쓰지 않고 SKIPPED다")
  void disabledCollectionDoesNotWriteAcl() {
    // given
    scenario.active("C1:1700000000.000001", "slack.message");
    scenario.conflictingPages();
    scenario.disabled();
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.SKIPPED);
    assertThat(scenario.acls("C1:1700000000.000001", "file:F2")).isEmpty();
    assertThat(scenario.accessCalls()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"source:colon", "source%percent"})
  @DisplayName("비정규 instance는 접근 호출이나 보관 없이 INCOMPLETE다")
  void nonCanonicalInstanceCannotStageAccess(String instance) {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive", instance);
    scenario.conflictingPages();
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.accessCalls()).isZero();
    assertThat(scenario.acls("file:F2")).isEmpty();
  }

  @Test
  @DisplayName("동시각 신원 상충 뒤에도 후속 ACL 페이지를 저장한다")
  void conflictingObservationDoesNotStopRemainingPages() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.conflictingPages();
    // when
    scenario.collect();
    if (!scenario.accessObservationsConflicted())
      throw new IllegalStateException("given 준비 실패: U7 동시각 관측이 상충해야 한다");
    // then
    assertThat(scenario.acls("file:F2"))
        .containsEntry(
            "file:F2", new RawAcl.Observed(Status.FAILED, Set.of(), AccessCollectionScenario.T1));
  }

  @Test
  @DisplayName("대상이 없어도 그룹과 신원 전체 집합을 요청한다")
  void emptyTargetsStillCollectDirectory() {
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.COMPLETE);
    assertThat(scenario.targets()).isEmpty();
  }

  @Test
  @DisplayName("호출자가 준 도메인 정책과 호출 실행기를 전달한다")
  void forwardsAccessPolicyAndCalls() {
    // when
    scenario.collect();
    // then
    assertThat(scenario.input().domains()).isSameAs(scenario.domains);
    assertThat(scenario.input().calls()).isSameAs(scenario.calls);
    assertThat(scenario.input().cancel()).isSameAs(scenario.cancel);
  }

  @Test
  @DisplayName("보충은 수집 체크포인트와 본문 관측을 바꾸지 않는다")
  void backfillPreservesBodyProgress() {
    // given
    scenario.active("C1:1700000000.000001", "slack.message");
    scenario.bodyProgress();
    // when
    scenario.collect();
    // then
    assertThat(scenario.memory.checkpoints().read(scenario.source.id()))
        .isEqualTo(
            new StoredCheckpoint(
                1, Optional.of(new Checkpoint(1, "{\"cursor\":\"body-cursor\"}"))));
    assertThat(scenario.memory.runs().observations(scenario.run))
        .containsExactlyInAnyOrder("C1:1700000000.000001", "C2:1700000000.000002");
  }

  @Test
  @DisplayName("접근 중 점유 인계는 페이지를 저장하지 않고 CLAIM_LOST다")
  void claimLostBeforeSinkWritesNothing() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    scenario.pages(
        new AclPage(
            Map.of(
                "file:F1", RawAcl.observed(Status.FAILED, Set.of(), AccessCollectionScenario.T1)),
            List.of(),
            List.of()));
    scenario.duringAccess(scenario::takeover);
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.CLAIM_LOST);
    assertThat(scenario.acls("file:F1")).isEmpty();
  }

  @Test
  @DisplayName("Tiro 재수집은 변경 없는 OK 노트도 포함한다")
  void tiroRefreshIncludesPreviouslyOkNotes() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("tiro");
    scenario.active("note:N1", "tiro.note");
    scenario.withAcl("note:N1", Status.OK);
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("note:N1");
  }

  @Test
  @DisplayName("Slack의 기존 OK 메시지도 채널 거부를 반영하도록 재관측한다")
  void previouslyOkSlackIsReobserved() {
    // given
    scenario.active("C1:1700000000.000001", "slack.message");
    scenario.withAcl("C1:1700000000.000001", Status.OK);
    scenario.pages(
        new AclPage(
            Map.of(
                "C1:1700000000.000001",
                RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, AccessCollectionScenario.T1)),
            List.of(),
            List.of()));
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("C1:1700000000.000001");
    assertThat(scenario.acls("C1:1700000000.000001").get("C1:1700000000.000001"))
        .isEqualTo(RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, AccessCollectionScenario.T1));
  }

  @Test
  @DisplayName("이번 run에서 관측한 OK 메시지는 다시 보충한다")
  void bodyObservedOkIsBackfilled() {
    // given
    scenario.active("C1:1700000000.000001", "slack.message");
    scenario.withAcl("C1:1700000000.000001", Status.OK);
    scenario.bodyObserved("C1:1700000000.000001");
    scenario.pages(
        new AclPage(
            Map.of(
                "C1:1700000000.000001",
                RawAcl.observed(
                    Status.OK,
                    Set.of(AccessKey.parse("group:access-source:C1")),
                    AccessCollectionScenario.T1)),
            List.of(),
            List.of()));
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("C1:1700000000.000001");
    assertThat(scenario.acls("C1:1700000000.000001").get("C1:1700000000.000001"))
        .isEqualTo(
            RawAcl.observed(
                Status.OK,
                Set.of(AccessKey.parse("group:access-source:C1")),
                AccessCollectionScenario.T1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN", "FAILED"})
  @DisplayName("Slack의 UNKNOWN 또는 FAILED 메시지도 보충한다")
  void retryableSlackAclIsBackfilled(String status) {
    // given
    scenario.active("C1:1700000000.000001", "slack.message");
    scenario.withAcl("C1:1700000000.000001", Status.valueOf(status));
    scenario.pages(
        new AclPage(
            Map.of(
                "C1:1700000000.000001",
                RawAcl.observed(
                    Status.OK,
                    Set.of(AccessKey.parse("group:access-source:C1")),
                    AccessCollectionScenario.T1)),
            List.of(),
            List.of()));
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("C1:1700000000.000001");
    assertThat(scenario.acls("C1:1700000000.000001").get("C1:1700000000.000001").status())
        .isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("접근 중 취소하면 후속 페이지를 저장하지 않고 INCOMPLETE다")
  void cancellationBeforeSinkWritesNothing() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.conflictingPages();
    scenario.duringAccess(scenario.cancel::cancel);
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.acls("file:F2")).isEmpty();
  }

  @Test
  @DisplayName("페이지가 없어도 접근 종료 전 점유를 잃으면 CLAIM_LOST다")
  void claimLostWithoutPageIsReported() {
    // given
    scenario.duringAccess(scenario::takeover);
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.CLAIM_LOST);
  }

  @Test
  @DisplayName("접근 전 점유를 잃으면 API를 부르지 않고 CLAIM_LOST다")
  void claimLostBeforeAccessDoesNotCallAdapter() {
    // given
    scenario.takeover();
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.CLAIM_LOST);
    assertThat(scenario.accessCalls()).isZero();
  }

  @Test
  @DisplayName("접근 보충으로 ACL이 바뀌면 투영을 요청한다")
  void backfillRequestsProjection() {
    // given
    scenario.active("C1:1700000000.000001", "slack.message");
    scenario.pages(
        new AclPage(
            Map.of(
                "C1:1700000000.000001",
                RawAcl.observed(
                    Status.OK,
                    Set.of(AccessKey.parse("group:access-source:C1")),
                    AccessCollectionScenario.T1)),
            List.of(),
            List.of()));
    // when
    scenario.collect();
    // then
    assertThat(scenario.memory.outbox().request(scenario.source.id())).isPresent();
  }

  @Test
  @DisplayName("500개를 넘는 원본도 마지막 페이지까지 한 번씩 선정한다")
  void scansAllActivePagesWithoutDuplicateTargets() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.activeFiles(501);
    scenario.bodyObserved("file:F1");
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets())
        .hasSize(501)
        .doesNotHaveDuplicates()
        .contains("file:F0", "file:F500");
  }

  @Test
  @DisplayName("로컬 비관측은 결정을 대신하지 않으며 누락 대상은 FAILED다")
  void localNotObservedDoesNotCompleteTarget() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("custom-nonrefresh");
    scenario.active("file:F1", "gdrive.file");
    scenario.unobservedResolution();
    // when
    scenario.collect();
    // then
    assertThat(scenario.acls("file:F1").get("file:F1").status()).isEqualTo(Status.FAILED);
  }

  @ParameterizedTest
  @CsvSource({"500,file:F499", "501,file:F500"})
  @DisplayName("배치 경계의 처음과 끝 대상은 FAILED로 닫는다")
  void missingTargetsAcrossBatchesFailClosed(int count, String lastId) {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.activeFiles(count);
    // when
    scenario.collect();
    // then
    assertThat(scenario.acls("file:F0", lastId))
        .hasSize(2)
        .allSatisfy((id, acl) -> assertThat(acl.status()).isEqualTo(Status.FAILED));
  }

  @ParameterizedTest
  @CsvSource({"500,1", "501,2"})
  @DisplayName("누락 대상의 실제 저장 배치마다 투영을 한 번 요청한다")
  void missingTargetBatchesRequestProjection(int count, long requests) {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.activeFiles(count);
    // when
    scenario.collect();
    // then
    assertThat(scenario.memory.outbox().request(scenario.source.id()).orElseThrow().version())
        .isEqualTo(requests);
  }

  @Test
  @DisplayName("접근 실패는 INCOMPLETE이며 이미 커밋한 제한을 유지한다")
  void providerFailurePreservesCommittedPages() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.conflictingPages();
    scenario.failAfterPages();
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.acls("file:F2"))
        .containsEntry(
            "file:F2", new RawAcl.Observed(Status.FAILED, Set.of(), AccessCollectionScenario.T1));
  }

  @Test
  @DisplayName("미지원 소스라도 취소된 run은 INCOMPLETE다")
  void cancelledUnsupportedSourceIsIncomplete() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("notion");
    scenario.cancel.cancel();
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.INCOMPLETE);
  }

  @ParameterizedTest
  @CsvSource({
    "COMPLETE,complete",
    "INCOMPLETE,incomplete",
    "CLAIM_LOST,claim_lost",
    "SKIPPED,skipped",
    "UNSUPPORTED,unsupported"
  })
  @DisplayName("접근 결과는 소문자 wire 값을 쓴다")
  void accessResultUsesLowercaseWire(AccessResult result, String expected) {
    // when
    String wire = result.wire();
    // then
    assertThat(wire).isEqualTo(expected);
  }

  @Test
  @DisplayName("heartbeat 저장 오류가 남으면 유효 점유라도 접근 호출을 막는다")
  void failedHeartbeatDoesNotCallAdapter() {
    // given
    scenario.heartbeatStorageFails();
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.accessCalls()).isZero();
  }

  @Test
  @DisplayName("대상 선정 중 점유를 잃으면 접근 API를 부르지 않는다")
  void claimLostDuringSelectionDoesNotCallAdapter() {
    // given
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    scenario.duringResolution(scenario::takeover);
    // when
    var result = scenario.collect();
    // then
    assertThat(result).isEqualTo(AccessResult.CLAIM_LOST);
    assertThat(scenario.accessCalls()).isZero();
  }

  @Test
  @DisplayName("run 전체 실패여도 상한에 도달한 이전 OK ACL은 FAILED로 닫는다")
  void expiredAclFailsClosedWhenProviderFails() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    scenario.observedAt("file:F1", AccessCollectionScenario.T1.minusSeconds(86400));
    scenario.failAfterPages();
    scenario.collect();
    // then
    assertThat(
            scenario
                .memory
                .rawAcls()
                .get(scenario.source.id(), List.of("file:F1"))
                .get("file:F1")
                .status())
        .isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("상한 직전 이전 OK ACL은 run 전체 실패로 만료시키지 않는다")
  void recentAclSurvivesRunFailure() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    scenario.observedAt("file:F1", AccessCollectionScenario.T1.minusSeconds(86399));
    scenario.failAfterPages();
    scenario.collect();
    // then
    assertThat(
            scenario
                .memory
                .rawAcls()
                .get(scenario.source.id(), List.of("file:F1"))
                .get("file:F1")
                .status())
        .isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("만료 제한은 마지막 원천 관측 시각을 바꾸지 않는다")
  void expiryPreservesObservationAge() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    var old = AccessCollectionScenario.T1.minusSeconds(86400);
    scenario.observedAt("file:F1", old);
    scenario.failAfterPages();
    scenario.collect();
    // then
    assertThat(
            scenario
                .memory
                .rawAcls()
                .get(scenario.source.id(), List.of("file:F1"))
                .get("file:F1")
                .observedAt())
        .isEqualTo(old);
  }

  @Test
  @DisplayName("Slack의 변경 없는 메시지는 정상 run에서 나이로 닫지 않는다")
  void refreshedSlackCarrierDoesNotExpire() {
    scenario.active("C1:1700000000.000001", "slack.message");
    scenario.observedAt("C1:1700000000.000001", AccessCollectionScenario.T1.minusSeconds(86400));
    scenario.pages(
        new AclPage(
            Map.of(
                "C1:1700000000.000001",
                RawAcl.observed(
                    Status.OK,
                    Set.of(AccessKey.parse("group:access-source:C1")),
                    AccessCollectionScenario.T1)),
            List.of(),
            List.of()));
    // when
    scenario.collect();
    // then
    assertThat(
            scenario
                .memory
                .rawAcls()
                .get(scenario.source.id(), List.of("C1:1700000000.000001"))
                .get("C1:1700000000.000001")
                .status())
        .isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("만료됐던 동일 권한의 신선한 관측은 다시 투영을 요청한다")
  void freshSameAclAfterExpiryRequestsProjection() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    scenario.observedAt("file:F1", AccessCollectionScenario.T1.minusSeconds(86400));
    scenario.pages(
        new AclPage(
            Map.of("file:F1", RawAcl.observed(Status.OK, Set.of(), AccessCollectionScenario.T1)),
            List.of(),
            List.of()));
    scenario.collect();
    // then
    assertThat(scenario.memory.outbox().request(scenario.source.id())).isPresent();
  }

  @Test
  @DisplayName("공급자가 오래된 관측을 정상 반환해도 만료는 제한한다")
  void staleProviderObservationExpiresAfterNormalReturn() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    var old = AccessCollectionScenario.T1.minusSeconds(86400);
    scenario.observedAt("file:F1", old);
    scenario.pages(
        new AclPage(
            Map.of("file:F1", RawAcl.observed(Status.OK, Set.of(), old)), List.of(), List.of()));
    scenario.collect();
    // then
    assertThat(scenario.acls("file:F1").get("file:F1").status()).isEqualTo(Status.FAILED);
    assertThat(scenario.memory.outbox().request(scenario.source.id())).isPresent();
  }

  @Test
  @DisplayName("공급자 실패 중 점유를 잃으면 CLAIM_LOST다")
  void claimLostDuringFailedProviderStillReportsClaimLost() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.duringAccess(scenario::takeover);
    scenario.failAfterPages();
    // then
    assertThat(scenario.collect()).isEqualTo(AccessResult.CLAIM_LOST);
  }

  @Test
  @DisplayName("만료 읽기 중 취소는 COMPLETE가 될 수 없다")
  void expiryReadCancellationCannotReportComplete() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.cancelOnExpiryRead();
    // then
    assertThat(scenario.collect()).isEqualTo(AccessResult.INCOMPLETE);
  }

  @Test
  @DisplayName("공급자 실패는 만료 실패 원인도 보존한다")
  void providerFailurePreservesExpiryFailureAsSuppressed() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.failProviderAndExpiryRead();
    scenario.collect();
    // then
    assertThat(scenario.providerFailure().getSuppressed())
        .extracting(Throwable::getMessage)
        .containsExactly("raw read unavailable");
  }

  @Test
  @DisplayName("점유 인계 뒤 만료 커밋은 원본을 바꾸지 못한다")
  void expiryCannotWriteAfterClaimTakeover() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.active("file:F1", "gdrive.file");
    scenario.observedAt("file:F1", AccessCollectionScenario.T1.minusSeconds(86400));
    scenario.takeover();
    // then
    assertThatThrownBy(scenario::expireDirectly).isInstanceOf(ClaimLostException.class);
    assertThat(scenario.acls("file:F1").get("file:F1").status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("취소는 다음 원본 페이지를 읽기 전에 중단한다")
  void cancellationStopsBeforeNextRawPage() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.activeFiles(501);
    scenario.cancelAfterFirstRawPage();
    scenario.collect();
    // then
    assertThat(scenario.rawReads()).isEqualTo(1);
  }

  @Test
  @DisplayName("취소는 다음 대상 해석 배치 전에 중단한다")
  void cancellationStopsBeforeNextResolutionBatch() {
    scenario.close();
    scenario = new AccessCollectionScenario("google-drive");
    scenario.activeFiles(501);
    scenario.duringResolution(scenario.cancel::cancel);
    scenario.collect();
    // then
    assertThat(scenario.resolutions()).isEqualTo(500);
  }
}
