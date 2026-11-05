package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.AccessResult;
import io.scos.engine.DocumentNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
