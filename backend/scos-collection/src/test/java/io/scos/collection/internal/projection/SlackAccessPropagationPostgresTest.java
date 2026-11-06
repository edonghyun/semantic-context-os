package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.AclCommit;
import io.scos.connector.slack.internal.FakeSlackServer.Fault;
import io.scos.engine.DocumentNotFoundException;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.DocumentAcl;
import io.scos.support.LogCapture;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated
@Tag("db")
@DisplayName("Slack 권한 변경은 본문과 독립적으로 제한 엔진에 전파된다(접근 설계 §5.2–5.5)")
class SlackAccessPropagationPostgresTest {
  private SlackAccessScenario scenario;

  @BeforeEach
  void setUp() throws Exception {
    scenario = new SlackAccessScenario();
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Nested
  @DisplayName("비참여 정회원에게 공개 채널 문서가 보일 때")
  class PublicChannel {
    @BeforeEach
    void givenPublicDocument() {
      scenario.givenPublicDocument();
    }

    @Test
    @DisplayName("비공개로 바뀌면 본문 변경 없이 다음 검색에서 사라진다")
    void publicToPrivateRevokesNonParticipant() {
      // given
      scenario.makePrivate();
      // when
      var result = scenario.collectAndProject();
      // then
      assertThat(result.upserted()).isZero();
      assertThat(scenario.search("U1").hits()).isEmpty();
    }

    @Test
    @DisplayName("전체 채널 목록이 비면 기존 채널 키를 모두 회수한다")
    void completeEmptyDirectoryRevokesAllGroups() {
      // given
      scenario.unlistAllChannels();
      // when
      var result = scenario.collectAndProject();
      // then
      assertThat(result.accessResult()).isEqualTo(AccessResult.COMPLETE);
      assertThat(scenario.channelKeys("U1")).isEmpty();
    }
  }

  @Nested
  @DisplayName("참여자에게 비공개 채널 문서가 보일 때")
  class PrivateChannel {
    @BeforeEach
    void givenPrivateDocument() {
      scenario.givenPrivateDocument();
    }

    @Test
    @DisplayName("참여자를 제거하면 다음 읽기는 문서 없음으로 거부한다")
    void removedMemberLosesPrivateChannel() {
      // given
      scenario.removeParticipant();
      // when
      scenario.collectAndProject();
      // then
      assertThatThrownBy(() -> scenario.read("U2"))
          .isExactlyInstanceOf(DocumentNotFoundException.class)
          .hasMessage("document not found: C1/day-2025-10-14");
    }

    @Test
    @DisplayName("본문 수집이 실패해도 제거된 참여자는 다음 검색에서 사라진다")
    void failedBodyStillRevokesMember() {
      // given
      scenario.removeParticipant();
      scenario.failBody();
      // when
      var result = scenario.collectAndProject();
      // then
      assertThat(result.errorKind()).contains("permission_denied");
      assertThat(scenario.search("U2").hits()).isEmpty();
    }
  }

  @Nested
  @DisplayName("별도 채널의 신원 누락으로 COMPLETE 전제가 깨질 때")
  class MissingIdentity {
    @BeforeEach
    void givenMissingIdentity() {
      scenario.givenPrivateDocumentWithU1();
      scenario.missingIdentityInOtherChannel();
    }

    @Test
    @DisplayName("INCOMPLETE 재병합에서도 제거된 참여자의 검색을 회수한다")
    void missingIdentityStillRevokesRemovedMember() {
      // given
      scenario.replaceParticipants("U2");
      // when
      var result = scenario.collectAndProject();
      // then
      assertThat(result.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
      assertThat(scenario.search("U1").hits()).isEmpty();
    }

    @Test
    @DisplayName("INCOMPLETE 재병합은 새 참여자의 검색 권한을 추가하지 않는다")
    void incompleteDirectoryCannotAddMember() {
      // given
      scenario.replaceParticipants("U1", "U2", "U3");
      // when
      var result = scenario.collectAndProject();
      // then
      assertThat(result.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
      assertThat(scenario.search("U3").hits()).isEmpty();
    }

    @Test
    @DisplayName("수집기의 정상 반환 뒤에도 누락 신원을 합성하지 않고 재병합한다")
    void missingIdentityFallsBackAfterNormalCollectorReturn() {
      // when
      var result = scenario.collectAndProject();
      // then
      assertThat(scenario.collectorReturnedNormally()).isTrue();
      assertThat(scenario.hasIdentity("U9")).isFalse();
      assertThat(result.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"info", "members"})
  @DisplayName("첫 run에서 채널 하나가 없어도 정상 그룹과 신원은 PG에 확정된다")
  void unreadableChannelStillCommitsHealthyDirectory(String method) {
    // given
    scenario.healthySecondChannel();
    scenario.failChannel(method, Fault.apiError("channel_not_found"));
    // when
    var run = scenario.collectAndProject();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.COMPLETE);
    assertThat(scenario.channelKeys("U1")).containsExactly("group:slack-a:C2");
    assertThat(scenario.hasIdentity("U1")).isTrue();
  }

  @Test
  @DisplayName("기존 채널이 사라지면 본문 변경 없이 문서 ACL을 빈 키로 닫는다")
  void unreadableChannelClosesExistingDocumentAcl() {
    // given
    scenario.givenPublicDocument();
    scenario.channelRemainsUnreadable();
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.documentAcl())
        .extracting(DocumentAcl::status, DocumentAcl::keys, DocumentAcl::reason)
        .containsExactly(DocumentAcl.Status.FAILED, Set.of(), AccessFailureReason.ACL_UNREADABLE);
    assertThat(scenario.search("U2").hits()).isEmpty();
  }

  @Test
  @DisplayName("연속 거부의 두 번째 run은 ACL 의미 변경이나 중복 닫기 경고를 만들지 않는다")
  void repeatedDenialDoesNotReopenCarrier() {
    // given
    scenario.givenUnreadableDocument();
    scenario.failChannel("info", Fault.apiError("channel_not_found"));
    try (var logs = LogCapture.of(AclCommit.class)) {
      // when
      var run = scenario.collect();
      // then
      assertThat(run.accessResult()).isEqualTo(AccessResult.COMPLETE);
      assertThat(scenario.projectionRequested()).isFalse();
      assertThat(logs.events("collection.acl.closed")).isEmpty();
    }
  }

  @Test
  @DisplayName("닫힌 채널 수와 ID는 접근 상태에 노출된다")
  void unreadableChannelAppearsInAccessStatus() {
    // given
    scenario.healthySecondChannel();
    scenario.failChannel("info", Fault.apiError("channel_not_found"));
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.accessStatus().aclUnreadable()).isEqualTo(1);
    assertThat(scenario.accessStatus().unreadableFiles()).containsExactly("C1");
  }

  @Test
  @DisplayName("다음 run에서 읽히면 채널 그룹과 문서 권한이 복구된다")
  void readableChannelRecoversOnNextRun() {
    // given
    scenario.givenUnreadableDocument();
    // when
    var run = scenario.collectAndProject();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.COMPLETE);
    assertThat(scenario.search("U1").hits()).hasSize(1);
    assertThat(scenario.accessStatus().unreadableFiles()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"info", "members"})
  @DisplayName("채널의 일시적 HTTP 장애는 여전히 INCOMPLETE이며 새 그룹을 열지 않는다")
  void channelHttpFailureKeepsDirectoryIncomplete(String method) {
    // given
    scenario.healthySecondChannel();
    scenario.failChannel(method, Fault.http(403, "channel_not_found"));
    // when
    var run = scenario.collectAndProject();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.channelKeys("U1")).isEmpty();
  }

  @Test
  @DisplayName("과거 원본을 보충하고 투영하면 ACL 누락과 다음 run의 추가 요청이 없다")
  void backfillThenProjectionLeavesNoMissingAcl() {
    // given
    scenario.givenHistoricalDocument();
    // when
    var result = scenario.backfillThenRepeat();
    // then
    assertThat(result.missing()).isZero();
    assertThat(result.additionalRequests()).isZero();
  }
}
