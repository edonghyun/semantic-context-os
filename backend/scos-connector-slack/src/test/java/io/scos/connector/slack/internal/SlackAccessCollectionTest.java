package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.slack.SlackAssembly;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceIdentityRecord;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Slack 접근 수집은 전체 디렉터리와 채널 권한을 관측한다(접근 §5.6)")
class SlackAccessCollectionTest {
  private final SlackAccessScenario s = new SlackAccessScenario();

  @AfterEach
  void close() {
    s.close();
  }

  private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

  @Test
  @DisplayName("API 기본 URL 끝의 슬래시는 요청 경로를 손상시키지 않는다")
  void apiBaseUrlMayEndInSlash() {
    s.baseUrlHasTrailingSlash();
    var result = s.collect();
    // then
    assertThat(result.failure()).isEmpty();
    assertThat(result.group("C1").members()).containsExactlyInAnyOrder("~members", "U2");
  }

  @ParameterizedTest(name = "채널 목록 HTTP {0}")
  @ValueSource(ints = {403, 404})
  @DisplayName("접근 채널 목록의 JSON HTTP 거부도 재시도하지 않는다")
  void conversationInventoryHttpDenialCannotRetryIntoComplete(int status) {
    s.failNext(
        "/api/users.conversations",
        FakeSlackServer.Fault.http(status, "{\"ok\":false,\"error\":\"access_denied\"}"));
    var result = s.collect();
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error ->
                assertThat(error.failure().kind()).isEqualTo(CallFailure.Kind.PERMISSION_DENIED));
    assertThat(s.waits()).isEmpty();
  }

  @Test
  @DisplayName("공백뿐인 이메일은 미관측 주소로 남긴다")
  void blankEmailIsAbsent() {
    s.user("U2", user -> ((ObjectNode) user.path("profile")).put("email", "  "));
    var result = s.collect();
    // then
    assertThat(result.identity("U2").email()).isEmpty();
  }

  @ParameterizedTest(name = "마지막 커서 {0}")
  @ValueSource(strings = {"{}", "{\"next_cursor\":\"\"}"})
  @DisplayName("빈 마지막 커서는 전체 디렉터리의 정상 종료다")
  void terminalCursorCompletesDirectory(String metadata) {
    s.failNext(
        "/api/users.list",
        FakeSlackServer.Fault.http(
            200, "{\"ok\":true,\"members\":[],\"response_metadata\":" + metadata + "}"));
    var result = s.collect();
    // then
    assertThat(result.failure()).isEmpty();
    assertThat(result.group("~members").members()).isEmpty();
  }

  @Test
  @DisplayName("목록에 있는 참여자는 게스트 신원으로 중복 관측하지 않는다")
  void listedParticipantIsNotReemittedAsGuest() {
    s.memberPages("[[\"U2\",\"U9\"]]");
    var result = s.collect();
    // then
    assertThat(result.identities())
        .extracting(SourceIdentityRecord::sourceUserId)
        .containsExactlyInAnyOrder("U1", "U2", "U9");
  }

  @ParameterizedTest(name = "HTTP {0}, ok={1}")
  @CsvSource({"403,false", "404,false", "403,true", "404,true"})
  @DisplayName("JSON 본문의 성공 여부와 무관하게 HTTP 거부는 채널을 닫는다")
  void jsonHttpDenialCannotRetryIntoComplete(int status, boolean ok) {
    s.failNext(
        "/api/conversations.info",
        FakeSlackServer.Fault.http(
            status,
            "{\"ok\":"
                + ok
                + ",\"error\":\"access_denied\",\"channel\":{\"id\":\"C1\",\"is_private\":false,\"is_im\":false,\"is_mpim\":false}}"));
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).isEmpty();
    assertThat(result.failure()).isPresent();
  }

  @ParameterizedTest(name = "HTTP {0}")
  @ValueSource(ints = {403, 404})
  @DisplayName("HTTP 접근 거부도 즉시 채널을 비우고 미완료로 끝낸다")
  void httpDeniedChannelIsRevokedAndIncomplete(int status) {
    s.failNext("/api/conversations.info", FakeSlackServer.Fault.http(status, "access denied"));
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).isEmpty();
    assertThat(result.failure()).isPresent();
  }

  @ParameterizedTest(name = "회원 정보 {0}")
  @ValueSource(
      strings = {
        "is_restricted",
        "is_ultra_restricted",
        "is_bot",
        "deleted",
        "team_id",
        "id",
        "profile"
      })
  @DisplayName("회원 자격 필수 정보 누락이면 디렉터리를 완료하지 않는다")
  void missingUserEligibilityIsIncomplete(String field) {
    s.user("U1", user -> user.remove(field));
    var result = s.collect();
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error ->
                assertThat(error.failure().kind())
                    .isEqualTo(CallFailure.Kind.INVALID_PROVIDER_RESPONSE));
  }

  @ParameterizedTest(name = "게스트 조건 {0}")
  @ValueSource(
      strings = {"is_restricted", "is_ultra_restricted", "is_stranger", "is_bot", "team_id"})
  @DisplayName("제한·외부·봇 신원을 게스트로 관측하여 읽기 키를 차단한다")
  void excludedIdentityIsGuest(String field) {
    s.user(
        "U1",
        user -> {
          if (field.equals("team_id")) user.put(field, "TOTHER");
          else user.put(field, true);
        });
    var result = s.collect();
    // then
    assertThat(result.identity("U1").guest()).isTrue();
  }

  @Test
  @DisplayName("제한·봇 필드가 없는 탈퇴자도 전체 순회를 완료한다")
  void deletedUserWithoutEligibilityFlagsCompletesDirectory() {
    s.deletedUserWithoutEligibilityFlags();
    var result = s.collect();
    // then
    assertThat(result.failure()).isEmpty();
  }

  @Test
  @DisplayName("이메일이 남은 탈퇴자는 정회원 그룹에 들어가지 않는다")
  void deletedUserWithEmailIsExcludedFromMembers() {
    s.deletedUserWithoutEligibilityFlags();
    var result = s.collect();
    // then
    assertThat(result.group("~members").members()).isEmpty();
  }

  @Test
  @DisplayName("필드가 빠진 탈퇴자의 이메일과 deleted 상태를 보존한다")
  void deletedUserWithoutFlagsKeepsDeletedIdentity() {
    s.deletedUserWithoutEligibilityFlags();
    var result = s.collect();
    // then
    assertThat(result.identity("U1"))
        .isEqualTo(
            new SourceIdentityRecord(
                "U1", Optional.of("u1@example.com"), false, false, true, SlackAccessScenario.NOW));
  }

  @Test
  @DisplayName("탈퇴 신원을 deleted로 관측한다")
  void deletedIdentityIsPreserved() {
    s.user("U1", user -> user.put("deleted", true));
    var result = s.collect();
    // then
    assertThat(result.identity("U1").deleted()).isTrue();
  }

  @Test
  @DisplayName("채널 목록이 비어도 전체 신원 디렉터리를 수집한다")
  void emptyInventoryStillCollectsDirectory() {
    s.unlistChannel();
    var result = s.collect();
    // then
    assertThat(result.group("~members").members()).containsExactly("U1");
    assertThat(result.failure()).isEmpty();
  }

  @ParameterizedTest(name = "응답 위반 {0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "/api/users.list|{\"ok\":true,\"members\":{}}",
        "/api/users.list|{\"ok\":true,\"members\":[],\"response_metadata\":{\"next_cursor\":123}}",
        "/api/auth.test|{\"ok\":true}",
        "/api/users.conversations|{\"ok\":true}",
        "/api/users.list|{\"ok\":true,\"members\":[],\"response_metadata\":false}"
      })
  @DisplayName("정상 HTTP의 잘못된 페이지 모양을 완료로 인정하지 않는다")
  void malformedDirectoryIsIncomplete(String path, String body) {
    s.failNext(path, FakeSlackServer.Fault.http(200, body));
    var result = s.collect();
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error ->
                assertThat(error.failure().kind())
                    .isEqualTo(CallFailure.Kind.INVALID_PROVIDER_RESPONSE));
  }

  @Test
  @DisplayName("본문 메시지도 출처 인스턴스의 채널 그룹 ACL을 관측한다")
  void bodyMessageCarriesChannelAcl() {
    var acl = s.bodyAcl();
    // then
    assertThat(acl)
        .isEqualTo(
            RawAcl.observed(
                DocumentAcl.Status.OK,
                Set.of(AccessKey.parse("group:slack-synthetic:C1")),
                SlackAccessScenario.NOW));
  }

  @Test
  @DisplayName("로컬 ACL 보충은 HTTP 호출 없이 채널 키를 계산한다")
  void localAclNeedsNoApi() {
    var acl = s.localAcl(SlackAccessScenario.MESSAGE);
    // then
    assertThat(acl)
        .isEqualTo(
            RawAcl.observed(
                DocumentAcl.Status.OK,
                Set.of(AccessKey.parse("group:slack-synthetic:C1")),
                SlackAccessScenario.NOW));
    assertThat(s.requests()).isEmpty();
  }

  @ParameterizedTest(name = "채널 거부 {0}")
  @ValueSource(strings = {"not_in_channel", "channel_not_found", "missing_scope"})
  @DisplayName("채널 조회를 거부하면 빈 그룹을 남기고 미완료로 끝낸다")
  void deniedChannelIsRevokedAndIncomplete(String code) {
    s.failNext("/api/conversations.members", FakeSlackServer.Fault.apiError(code));
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).isEmpty();
    assertThat(result.failure()).isPresent();
  }

  @Test
  @DisplayName("한 채널 거부 뒤에도 다른 채널의 회수를 관측한다")
  void deniedChannelDoesNotStopOtherChannels() {
    s.secondChannel();
    s.failNext("/api/conversations.info", FakeSlackServer.Fault.apiError("missing_scope"));
    var result = s.collect();
    // then
    assertThat(result.group("C2").members()).containsExactly("U1");
    assertThat(result.failure()).isPresent();
  }

  @Test
  @DisplayName("채널 공개 여부가 누락되면 정회원에게 열지 않는다")
  void ambiguousChannelIsEmptyAndIncomplete() {
    s.changeAccess(
        access -> ((ObjectNode) access.path("conversations").path("C1")).remove("is_private"));
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).isEmpty();
    assertThat(result.failure()).isPresent();
  }

  @Test
  @DisplayName("users.list에 없는 참여자는 제한된 게스트 신원으로 관측한다")
  void unlistedParticipantIsRestrictedIdentity() {
    s.memberPages("[[\"U2\",\"U9\"]]");
    var result = s.collect();
    // then
    assertThat(result.identity("U9"))
        .isEqualTo(
            new SourceIdentityRecord(
                "U9", Optional.empty(), false, true, false, SlackAccessScenario.NOW));
    assertThat(result.failure()).isEmpty();
  }

  @Test
  @DisplayName("신원 목록 전 페이지를 정회원 합성 그룹에 합친다")
  void allUserPagesAreCombined() {
    s.splitUsersAcrossPages();
    s.user("U2", user -> user.put("is_restricted", false));
    var result = s.collect();
    // then
    assertThat(result.group("~members").members()).containsExactlyInAnyOrder("U1", "U2");
    assertThat(result.failure()).isEmpty();
  }

  @Test
  @DisplayName("신원 페이지가 누락되면 정상 완료로 반환하지 않는다")
  void missingUserPageIsIncomplete() {
    s.changeAccess(access -> access.remove("userPages"));
    var result = s.collect();
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error ->
                assertThat(error.failure().kind())
                    .isEqualTo(CallFailure.Kind.INVALID_PROVIDER_RESPONSE));
  }

  @Test
  @DisplayName("신원 목록 실패에도 모든 선정 대상의 ACL을 낸다")
  void directoryFailureStillEmitsEveryTargetAcl() {
    s.failNext("/api/users.list", FakeSlackServer.Fault.apiError("missing_scope"));
    var result = s.collect(SlackAccessScenario.MESSAGE, "malformed");
    // then
    assertThat(result.acls()).containsOnlyKeys(SlackAccessScenario.MESSAGE, "malformed");
  }

  @Test
  @DisplayName("요청 제한은 기존 실행기의 Retry-After로 재시도한다")
  void rateLimitUsesCallExecutor() {
    s.failNext("/api/users.list", FakeSlackServer.Fault.rateLimited(2));
    var result = s.collect();
    // then
    assertThat(result.failure()).isEmpty();
    assertThat(s.waits()).containsExactly(RETRY_DELAY);
  }

  @Test
  @DisplayName("취소된 접근 수집은 요청을 시작하지 않는다")
  void cancelledCollectionMakesNoRequests() {
    s.cancel();
    var result = s.collect(SlackAccessScenario.MESSAGE);
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error -> assertThat(error.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
    assertThat(s.requests()).isEmpty();
  }

  @Test
  @DisplayName("sink 저장 실패는 채널 실패로 삼키지 않는다")
  void sinkFailureStopsImmediately() {
    s.failSinkOnGroup("C1");
    var result = s.collect();
    // then
    assertThat(result.failure())
        .hasValueSatisfying(
            failure ->
                assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("injected ACL sink failure"));
    assertThat(s.requests()).noneMatch(request -> request.startsWith("/api/users.list"));
  }

  @Test
  @DisplayName("공개 채널은 정회원 그룹 참조와 참여자를 포함한다")
  void publicChannelContainsMembersReference() {
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).containsExactlyInAnyOrder("~members", "U2");
    assertThat(result.group("~members").members()).containsExactly("U1");
  }

  @Test
  @DisplayName("비공개 채널은 참여자만 포함한다")
  void privateChannelContainsOnlyParticipants() {
    s.channelFlag("is_private", true);
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("DM은 참여자만 포함한다")
  void dmUsesParticipants() {
    s.channelFlag("is_im", true);
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("MPIM은 참여자만 포함한다")
  void mpimUsesParticipants() {
    s.channelFlag("is_mpim", true);
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).containsExactly("U2");
  }

  @ParameterizedTest
  @ValueSource(strings = {"is_im", "is_mpim"})
  @DisplayName("is_private 없는 DM·그룹 DM도 참여자만 포함한다")
  void directMessageWithoutPrivateFlagUsesParticipants(String flag) {
    s.directMessageWithoutPrivateFlag(flag);
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).containsExactly("U2");
  }

  @ParameterizedTest
  @ValueSource(strings = {"is_im", "is_mpim"})
  @DisplayName("is_private 없는 DM·그룹 DM 순회도 COMPLETE로 끝난다")
  void directMessageWithoutPrivateFlagCompletes(String flag) {
    s.directMessageWithoutPrivateFlag(flag);
    // when·then
    assertThat(s.collect().failure()).isEmpty();
  }

  @ParameterizedTest(name = "제외 조건 {0}")
  @ValueSource(
      strings = {
        "is_restricted",
        "is_ultra_restricted",
        "is_stranger",
        "is_bot",
        "deleted",
        "team_id"
      })
  @DisplayName("정회원 그룹에서 제한·외부·봇·탈퇴 사용자를 제외한다")
  void regularMembersExcludeRestrictedUsers(String reason) {
    s.user(
        "U2",
        user -> {
          user.put("is_restricted", false);
          if (reason.equals("team_id")) user.put("team_id", "TOTHER");
          else user.put(reason, true);
        });
    var result = s.collect();
    // then
    assertThat(result.group("~members").members()).containsExactly("U1");
  }

  @ParameterizedTest(name = "is_email_confirmed={0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("이메일과 원천의 확인 여부를 그대로 관측한다")
  void identityUsesEmailConfirmation(boolean confirmed) {
    s.user("U1", user -> user.put("is_email_confirmed", confirmed));
    var result = s.collect();
    // then
    assertThat(result.identity("U1"))
        .isEqualTo(
            new SourceIdentityRecord(
                "U1",
                Optional.of("u1@example.com"),
                confirmed,
                false,
                false,
                SlackAccessScenario.NOW));
  }

  @Test
  @DisplayName("채널 멤버 전 페이지를 하나의 전체 집합으로 낸다")
  void allMemberPagesAreCombined() {
    s.channelFlag("is_private", true);
    s.memberPages("[[\"U1\"],[\"U2\"]]");
    var result = s.collect();
    // then
    assertThat(result.groups().stream().filter(group -> group.groupId().equals("C1"))).hasSize(1);
    assertThat(result.group("C1").members()).containsExactlyInAnyOrder("U1", "U2");
  }

  @Test
  @DisplayName("이메일이 없어도 원천 신원을 보존한다")
  void everyMemberHasIdentityEvenWithoutEmail() {
    s.user("U2", user -> ((ObjectNode) user.get("profile")).remove("email"));
    var result = s.collect();
    // then
    assertThat(result.identity("U2"))
        .isEqualTo(
            new SourceIdentityRecord(
                "U2", Optional.empty(), false, true, false, SlackAccessScenario.NOW));
  }

  @ParameterizedTest(name = "커서 순환 {0}")
  @ValueSource(
      strings = {"/api/users.conversations", "/api/conversations.members", "/api/users.list"})
  @DisplayName("커서가 반복되면 접근 전체 수집을 완료하지 않는다")
  void cursorCycleIsIncomplete(String path) {
    s.repeatCursor(path);
    var result = s.collect();
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error ->
                assertThat(error.failure().kind())
                    .isEqualTo(CallFailure.Kind.INVALID_PROVIDER_RESPONSE));
  }

  @Test
  @DisplayName("신원 목록 scope 누락이면 전체 디렉터리를 완료하지 않는다")
  void missingScopeDoesNotCompleteDirectory() {
    s.failNext("/api/users.list", FakeSlackServer.Fault.apiError("missing_scope"));
    var result = s.collect();
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error ->
                assertThat(error.failure().kind()).isEqualTo(CallFailure.Kind.PERMISSION_DENIED));
  }

  @Test
  @DisplayName("보관된 채널도 기존 멤버를 관측한다")
  void archivedChannelKeepsMembers() {
    s.channelFlag("is_archived", true);
    s.memberPages("[[\"U1\"]]");
    var result = s.collect();
    // then
    assertThat(result.group("C1").members()).contains("U1");
  }

  @Test
  @DisplayName("접근 채널 목록은 DM·MPIM까지 요청한다")
  void conversationInventoryRequestsAllTypes() {
    s.collect();
    // then
    assertThat(s.requests())
        .anySatisfy(
            request ->
                assertThat(request)
                    .contains(
                        "/api/users.conversations?",
                        "types=public_channel,private_channel,mpim,im"));
  }

  @Test
  @DisplayName("접근 채널 목록은 보관 채널을 제외하지 않는다")
  void conversationInventoryIncludesArchived() {
    s.collect();
    // then
    assertThat(s.requests())
        .anySatisfy(
            request ->
                assertThat(request)
                    .contains("/api/users.conversations?", "exclude_archived=false"));
  }

  @Test
  @DisplayName("현재 전체 목록에 없는 과거 채널은 완료 병합에서 비워진다")
  void unlistedOldChannelIsRevokedOnComplete() {
    var result = s.collect();
    // then
    assertThat(result.mergedOldChannelMembers()).isEmpty();
  }

  @ParameterizedTest(name = "잘못된 원본 id {0}")
  @ValueSource(
      strings = {
        "1700000000.000001",
        ":1700000000.000001",
        "C1:",
        "C1:bad",
        "~members:1700000000.000001"
      })
  @DisplayName("채널·시각이 잘못된 과거 id는 FAILED로 닫는다")
  void malformedExternalIdIsFailed(String id) {
    var result = s.collect(id);
    // then
    assertThat(result.acls())
        .containsEntry(
            id, RawAcl.observed(DocumentAcl.Status.FAILED, Set.of(), SlackAccessScenario.NOW));
  }

  @Test
  @DisplayName("원본 키는 모든 선정 대상에 대해 API 없이 채널 그룹으로 계산한다")
  void everySelectedTargetReceivesAcl() {
    var result = s.collect(SlackAccessScenario.MESSAGE, "COLD:1700000000.000002");
    // then
    assertThat(result.acls())
        .containsOnlyKeys(SlackAccessScenario.MESSAGE, "COLD:1700000000.000002");
    assertThat(result.acls().get(SlackAccessScenario.MESSAGE))
        .isEqualTo(
            RawAcl.observed(
                DocumentAcl.Status.OK,
                Set.of(new AccessKey(AccessKey.Kind.GROUP, SlackAssembly.SOURCE, "C1")),
                SlackAccessScenario.NOW));
  }
}
