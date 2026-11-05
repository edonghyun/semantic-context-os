package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceIdentityRecord;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Drive 접근은 모든 파일의 유효 권한 전체를 관측한다(T-b13)")
class DriveAccessCollectionTest {
  private final DriveAccessScenario scenario = new DriveAccessScenario();

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("확정 독자 거부 파일 id를 접근 결과에 남긴다")
  void unreadableFileIdsAreReported() {
    scenario.forbiddenReason("F1", "insufficientFilePermissions");
    scenario.permissions("F2", scenario.user());
    var result = scenario.collect("file:F1", "file:F2");
    // then
    assertThat(result.aclUnreadable()).containsExactly("file:F1");
  }

  @Test
  @DisplayName("독자 전용 파일의 확정 실패는 run 완료를 막지 않는다")
  void unreadableAclDoesNotMakeRunIncomplete() {
    scenario.forbiddenReason("F1", "insufficientFilePermissions");
    scenario.permissions("F2", scenario.user());
    scenario.permissions("F3", scenario.user());
    var result = scenario.collect("file:F1", "file:F2", "file:F3");
    // then
    assertThat(result.failure()).isEmpty();
  }

  @Test
  @DisplayName("목록 첫 파일이 독자 거부여도 뒤 파일 권한을 관측한다")
  void observesFilesAfterUnreadableFirstFile() {
    scenario.forbiddenReason("F1", "insufficientFilePermissions");
    scenario.permissions("F2", scenario.user());
    scenario.permissions("F3", scenario.user());
    var result = scenario.collect("file:F1", "file:F2", "file:F3");
    // then
    assertThat(result.acl("file:F3")).isEqualTo(ok("user:drive-a:a@example.com"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"forbidden", "rateLimitExceeded"})
  @DisplayName("독자 거부와 다른 이유가 섞인 403은 run 미완료다")
  void mixedForbiddenReasonsRemainIncomplete(String reason) {
    scenario.forbiddenBody(
        "F1",
        "{\"error\":{\"errors\":[{\"reason\":\"insufficientFilePermissions\"},{\"reason\":\""
            + reason
            + "\"}]}}");
    // when·then
    assertThat(scenario.collect("file:F1").failure()).isPresent();
  }

  @Test
  @DisplayName("독자 전용 파일은 접근 키 없는 FAILED로 닫는다")
  void unreadableAclRemainsClosed() {
    scenario.forbiddenReason("F1", "insufficientFilePermissions");
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acl("file:F1"))
        .isEqualTo(RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, DriveAccessScenario.NOW));
  }

  @ParameterizedTest
  @ValueSource(strings = {"rateLimitExceeded", "userRateLimitExceeded", "forbidden", "notFound"})
  @DisplayName("확정 독자 거부가 아닌 403은 run 미완료를 유지한다")
  void otherForbiddenReasonRemainsIncomplete(String reason) {
    scenario.forbiddenReason("F1", reason);
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.failure()).isPresent();
  }

  @Test
  @DisplayName("읽을 수 없는 403 본문은 run 미완료를 유지한다")
  void unreadableForbiddenBodyRemainsIncomplete() {
    scenario.forbiddenBody("F1", "not-json");
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.failure()).isPresent();
  }

  @Test
  @DisplayName("독자 거부와 rate limit이 섞이면 확정 거부 대신 재시도한다")
  void mixedRateLimitIsRetried() {
    scenario.forbiddenBody(
        "F1",
        "{\"error\":{\"errors\":[{\"reason\":\"insufficientFilePermissions\"},{\"reason\":\"rateLimitExceeded\"}]}}");
    scenario.collect("file:F1");
    // then
    assertThat(
            scenario.requests().stream()
                .filter(request -> request.path().endsWith("/permissions"))
                .count())
        .isGreaterThan(1);
  }

  @Test
  @DisplayName("UNVERIFIED 설정 차단은 공급자 장애와 구분한다")
  void unverifiedVisibilityHasSpecificReason() {
    scenario.visibility("\"UNVERIFIED\"");
    // when·then
    assertThat(((RawAcl.Observed) scenario.collect("file:F1").acl("file:F1")).reason().wire())
        .isEqualTo("visibility_unverified");
  }

  @Test
  @DisplayName("UNVERIFIED 설정에서는 버릴 권한 API 응답을 요청하지 않는다")
  void unverifiedVisibilityMakesNoPermissionCalls() {
    scenario.visibility("\"UNVERIFIED\"");
    scenario.collect("file:F1");
    // then
    assertThat(scenario.requests()).isEmpty();
  }

  @Test
  @DisplayName("키를 만들 수 없는 인스턴스의 FAILED 권한은 미완료로 끝낸다")
  void nonCanonicalInstanceRemainsIncomplete() {
    scenario.nonCanonicalInstance();
    // when·then
    assertThat(scenario.collect("file:F1").failure()).isPresent();
  }

  @Test
  @DisplayName("직접 사용자는 소문자 이메일 키가 된다")
  void directUserBecomesLowercaseEmailKey() {
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok("user:drive-a:a@example.com"));
  }

  @Test
  @DisplayName("직접 사용자는 허용 도메인으로 검증한 이메일 신원을 낸다")
  void directUserIdentityIsVerifiedByDomain() {
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.identities())
        .containsExactly(
            new SourceIdentityRecord(
                "a@example.com",
                Optional.of("a@example.com"),
                true,
                false,
                false,
                DriveAccessScenario.NOW));
  }

  @Test
  @DisplayName("외부 이메일 신원은 검증하지 않는다")
  void externalIdentityIsNotVerified() {
    // given
    scenario.permissions("F1", scenario.user().replace("EXAMPLE.COM", "OTHER.COM"));
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.identities())
        .singleElement()
        .satisfies(identity -> assertThat(identity.emailVerified()).isFalse());
  }

  @Test
  @DisplayName("도메인 권한은 조직 허용 목록과 원천 도메인의 교집합만 쓴다")
  void domainGrantUsesOnlyOrganizationDomain() {
    // given
    scenario.permissions(
        "F1",
        "{\"id\":\"d1\",\"type\":\"domain\",\"role\":\"reader\",\"domain\":\"EXAMPLE.COM\"}",
        "{\"id\":\"d2\",\"type\":\"domain\",\"role\":\"reader\",\"domain\":\"other.com\"}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok("domain:drive-a:example.com"));
  }

  @Test
  @DisplayName("조직 허용 목록에 없는 도메인은 권한을 주지 않는다")
  void domainOutsideAllowlistNeverGrants() {
    // given
    scenario.organization(Set.of("other.com"), "example.com");
    scenario.permissions(
        "F1", "{\"id\":\"d\",\"type\":\"domain\",\"role\":\"reader\",\"domain\":\"example.com\"}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok());
  }

  @Test
  @DisplayName("오버레이한 원천 도메인만 도메인 권한을 준다")
  void domainOverlayControlsGrant() {
    // given
    scenario.organization(Set.of("other.com", "example.com"), "other.com");
    scenario.permissions(
        "F1",
        "{\"id\":\"d1\",\"type\":\"domain\",\"role\":\"reader\",\"domain\":\"example.com\"}",
        "{\"id\":\"d2\",\"type\":\"domain\",\"role\":\"reader\",\"domain\":\"other.com\"}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok("domain:drive-a:other.com"));
  }

  @Test
  @DisplayName("링크가 있는 누구나 권한은 아무 키도 주지 않는다")
  void anyoneNeverGrants() {
    // given
    scenario.permissions("F1", "{\"id\":\"anyone\",\"type\":\"anyone\",\"role\":\"reader\"}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-10-13T23:59:59Z", "2026-10-14T00:00:00Z"})
  @DisplayName("만료 시각 이하의 권한은 제외한다")
  void expiredPermissionNeverGrants(String expiry) {
    // given
    scenario.permissions(
        "F1",
        scenario.user().replace("\"deleted\":false", "\"expirationTime\":\"" + expiry + "\""));
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok());
    assertThat(result.identities()).isEmpty();
  }

  @Test
  @DisplayName("미래에 만료하는 권한은 허용한다")
  void futurePermissionGrants() {
    // given
    scenario.permissions(
        "F1",
        scenario
            .user()
            .replace("\"deleted\":false", "\"expirationTime\":\"2026-10-14T00:00:01Z\""));
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok("user:drive-a:a@example.com"));
  }

  @Test
  @DisplayName("삭제된 권한은 제외한다")
  void deletedPermissionNeverGrants() {
    // given
    scenario.permissions("F1", scenario.user().replace("\"deleted\":false", "\"deleted\":true"));
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok());
    assertThat(result.identities()).isEmpty();
  }

  @Test
  @DisplayName("Directory 없는 그룹 권한은 키만 만들고 구성원은 만들지 않는다")
  void groupWithoutDirectoryCreatesNoMembers() {
    // given
    scenario.permissions("F1", scenario.user().replace("\"user\"", "\"group\""));
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok("group:drive-a:a@example.com"));
    assertThat(result.groups()).isEmpty();
    assertThat(result.identities()).isEmpty();
  }

  @Test
  @DisplayName("두 권한 페이지를 모두 읽어 키의 합집합을 낸다")
  void allPermissionPagesAreRequired() {
    // given
    scenario.pages(
        "F1",
        "{\"permissions\":[" + scenario.user() + "],\"nextPageToken\":\"page-2\"}",
        "{\"permissions\":["
            + scenario.user().replace("permission-alice", "permission-bob").replace("A@", "B@")
            + "]}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls())
        .containsEntry("file:F1", ok("user:drive-a:a@example.com", "user:drive-a:b@example.com"));
    assertThat(scenario.requests()).hasSize(2);
    assertThat(scenario.requests().getLast().params()).containsEntry("pageToken", "page-2");
  }

  @Test
  @DisplayName("공유 드라이브 지원과 전체 권한 필드를 요청한다")
  void permissionRequestIncludesRequiredFields() {
    // when
    scenario.collect("file:F1");
    // then
    assertThat(scenario.requests())
        .singleElement()
        .satisfies(
            request ->
                assertThat(request.params())
                    .containsEntry("supportsAllDrives", "true")
                    .containsEntry(
                        "fields",
                        "nextPageToken,permissions(id,type,role,emailAddress,domain,deleted,expirationTime,permissionDetails)"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"permissions\":{}}", "{\"permissions\":null}"})
  @DisplayName("permissions 누락이나 비배열은 FAILED로 닫는다")
  void missingPermissionsIsFailed(String body) {
    // given
    scenario.response("F1", body);
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", failed());
  }

  @Test
  @DisplayName("뒤 페이지가 실패하면 앞 페이지의 권한도 부여하지 않는다")
  void failedLaterPageDiscardsPartialPermissions() {
    // given
    scenario.pages(
        "F1", "{\"permissions\":[" + scenario.user() + "],\"nextPageToken\":\"page-2\"}", "{}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", failed());
    assertThat(result.identities()).isEmpty();
  }

  @Test
  @DisplayName("권한 전체성을 검증하지 않았으면 200 응답도 FAILED다")
  void unverifiedPermissionVisibilityIsFailed() {
    // given
    scenario.visibility("\"UNVERIFIED\"");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls())
        .containsEntry(
            "file:F1",
            RawAcl.failed(AccessFailureReason.VISIBILITY_UNVERIFIED, DriveAccessScenario.NOW));
    assertThat(result.identities()).isEmpty();
    assertThat(result.failure()).isPresent();
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404})
  @DisplayName("권한 거부 파일은 삭제하지 않고 FAILED만 낸다")
  void forbiddenFileRemainsActiveButBlocked(int status) {
    // given
    scenario.forbidden("F1", status);
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", failed());
    assertThat(scenario.requests())
        .singleElement()
        .satisfies(request -> assertThat(request.method()).isEqualTo("GET"));
  }

  @Test
  @DisplayName("정상 파일과 거부 파일이 섞이면 접근을 미완료로 반환한다")
  void fileFailureMakesAccessIncomplete() {
    // given
    scenario.forbidden("F2", 403);
    // when
    var result = scenario.collect("file:F1", "file:F2");
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure ->
                assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.PERMISSION_DENIED));
    assertThat(result.acls())
        .containsEntry("file:F1", ok("user:drive-a:a@example.com"))
        .containsEntry("file:F2", failed());
  }

  @Test
  @DisplayName("실패한 파일 뒤의 대상도 빠짐없이 관측한다")
  void fileFailureDoesNotSkipRemainingTargets() {
    // given
    scenario.forbidden("F2", 403);
    scenario.permissions("F3", scenario.user().replace("A@", "C@"));
    // when
    var result = scenario.collect("file:F1", "file:F2", "file:F3");
    // then
    assertThat(result.acls())
        .containsOnlyKeys("file:F1", "file:F2", "file:F3")
        .containsEntry("file:F3", ok("user:drive-a:c@example.com"));
  }

  @Test
  @DisplayName("모든 파일 권한 목록이 완전하면 정상 반환한다")
  void completePermissionInventoryReturnsNormally() {
    // given
    scenario.permissions("F2");
    // when
    var result = scenario.collect("file:F1", "file:F2");
    // then
    assertThat(result.failure()).isEmpty();
    assertThat(result.acls())
        .containsEntry("file:F1", ok("user:drive-a:a@example.com"))
        .containsEntry("file:F2", ok());
  }

  @Test
  @DisplayName("드라이브 문서는 자기 권한만 쓰고 파일에 보태지 않는다")
  void driveDocumentUsesOwnMembershipPermissions() {
    // given
    scenario.permissions("D1", scenario.user().replace("A@", "DRIVE@"));
    // when
    var result = scenario.collect("drive:D1", "file:F1");
    // then
    assertThat(result.acls())
        .containsEntry("drive:D1", ok("user:drive-a:drive@example.com"))
        .containsEntry("file:F1", ok("user:drive-a:a@example.com"));
  }

  @Test
  @DisplayName("carrier가 아닌 시트 조각·바이너리 대상은 API 호출 없이 FAILED다")
  void fragmentsHaveNoAcl() {
    // when
    var result = scenario.collect("sheet:F1:S1:A1", "binary:F1");
    // then
    assertThat(result.acls())
        .containsEntry("sheet:F1:S1:A1", failed())
        .containsEntry("binary:F1", failed());
    assertThat(scenario.requests()).isEmpty();
    assertThat(result.failure()).isPresent();
  }

  @Test
  @DisplayName("대상이 비어 있으면 Directory 미설정 상태로 정상 반환한다")
  void noTargetsReturnsNormally() {
    // when
    var result = scenario.collect();
    // then
    assertThat(result.failure()).isEmpty();
    assertThat(result.acls()).isEmpty();
    assertThat(scenario.requests()).isEmpty();
  }

  @Test
  @DisplayName("sink 저장 실패는 남은 파일을 읽지 않고 전파한다")
  void sinkFailureStopsCollection() {
    // given
    scenario.failSink();
    // when
    var result = scenario.collect("file:F1", "file:F2");
    // then
    assertThat(result.failure().orElseThrow())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected access sink failure");
    assertThat(scenario.requests()).hasSize(1);
  }

  @Test
  @DisplayName("시작 전에 취소하면 외부 요청을 하지 않는다")
  void cancellationPreventsRequests() {
    // given
    scenario.cancelBeforeCollect();
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure -> assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
    assertThat(scenario.requests()).isEmpty();
  }

  @Test
  @DisplayName("페이지 저장 후 취소하면 다음 파일을 읽지 않는다")
  void cancellationStopsRemainingTargets() {
    // given
    scenario.cancelAfterPage();
    // when
    var result = scenario.collect("file:F1", "file:F2");
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure -> assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
    assertThat(scenario.requests()).hasSize(1);
  }

  @Test
  @DisplayName("반복 커서는 파일을 FAILED로 닫고 미완료로 끝낸다")
  void cursorCycleIsIncomplete() {
    // given
    scenario.pages(
        "F1",
        "{\"permissions\":[],\"nextPageToken\":\"cycle\"}",
        "{\"permissions\":[],\"nextPageToken\":\"cycle\"}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", failed());
    assertThat(result.failure()).isPresent();
    assertThat(scenario.requests()).hasSize(2);
  }

  @Test
  @DisplayName("기존 설정은 권한 전체성을 미검증으로 취급한다")
  void defaultVisibilityIsUnverified() {
    // given
    scenario.unverified();
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls())
        .containsEntry(
            "file:F1",
            RawAcl.failed(AccessFailureReason.VISIBILITY_UNVERIFIED, DriveAccessScenario.NOW));
    assertThat(result.failure()).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "true", "0", "\"FULL\"", "\"verified_full\""})
  @DisplayName("잘못된 전체성 설정은 invalid_configuration으로 거부한다")
  void invalidVisibilityIsRejected(String value) {
    // given
    scenario.visibility(value);
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure ->
                assertThat(failure.failure().kind())
                    .isEqualTo(CallFailure.Kind.INVALID_CONFIGURATION));
    assertThat(scenario.requests()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{}",
        "{\"id\":\"p\",\"type\":\"user\",\"role\":\"reader\"}",
        "{\"id\":\"p\",\"type\":\"group\",\"role\":\"reader\",\"emailAddress\":true}",
        "{\"id\":\"p\",\"type\":\"domain\",\"role\":\"reader\"}",
        "{\"id\":\"p\",\"type\":\"unknown\",\"role\":\"reader\"}",
        "{\"id\":\"p\",\"type\":\"user\",\"role\":\"none\",\"emailAddress\":\"a@example.com\"}",
        "{\"id\":\"p\",\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"invalid\"}"
      })
  @DisplayName("잘못된 권한 항목은 파일 전체를 FAILED로 닫는다")
  void malformedPermissionIsFailed(String permission) {
    // given
    scenario.permissions("F1", scenario.user(), permission);
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", failed());
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure ->
                assertThat(failure.failure().kind())
                    .isEqualTo(CallFailure.Kind.INVALID_PROVIDER_RESPONSE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"deleted\":\"false\"",
        "\"expirationTime\":\"bad-time\"",
        "\"expirationTime\":null"
      })
  @DisplayName("잘못된 삭제나 만료 필드는 권한 부여를 차단한다")
  void malformedRestrictionIsFailed(String field) {
    // given
    scenario.permissions("F1", scenario.user().replace("\"deleted\":false", field));
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", failed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "false", "3"})
  @DisplayName("문자열이 아닌 다음 페이지 토큰은 FAILED로 닫는다")
  void malformedCursorIsFailed(String cursor) {
    // given
    scenario.response("F1", "{\"permissions\":[],\"nextPageToken\":" + cursor + "}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", failed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"deleted", "email", "type", "expiration", "role"})
  @DisplayName("페이지 사이 같은 권한 id가 상충하면 파일 전체를 차단한다")
  void conflictingPermissionAcrossPagesIsFailed(String conflict) {
    // given
    String changed =
        switch (conflict) {
          case "deleted" -> scenario.user().replace("\"deleted\":false", "\"deleted\":true");
          case "email" -> scenario.user().replace("A@", "OTHER@");
          case "type" -> scenario.user().replace("\"user\"", "\"group\"");
          case "expiration" ->
              scenario
                  .user()
                  .replace("\"deleted\":false", "\"expirationTime\":\"2026-10-14T00:00:00Z\"");
          case "role" -> scenario.user().replace("\"reader\"", "\"writer\"");
          default -> throw new IllegalArgumentException(conflict);
        };
    scenario.pages(
        "F1",
        "{\"permissions\":[" + scenario.user() + "],\"nextPageToken\":\"page-2\"}",
        "{\"permissions\":[" + changed + "]}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", failed());
    assertThat(result.failure()).isPresent();
    assertThat(result.identities()).isEmpty();
  }

  @Test
  @DisplayName("한 페이지 안의 같은 권한 id가 상충해도 파일을 차단한다")
  void conflictingPermissionWithinPageIsFailed() {
    // given
    scenario.permissions("F1", scenario.user(), scenario.user().replace("A@", "OTHER@"));
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", failed());
    assertThat(result.failure()).isPresent();
  }

  @Test
  @DisplayName("페이지 사이 동일한 권한이 중복되면 권한을 유지한다")
  void identicalPermissionAcrossPagesIsAccepted() {
    // given
    scenario.pages(
        "F1",
        "{\"permissions\":[" + scenario.user() + "],\"nextPageToken\":\"page-2\"}",
        "{\"permissions\":[" + scenario.user() + "]}");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.acls()).containsEntry("file:F1", ok("user:drive-a:a@example.com"));
    assertThat(result.failure()).isEmpty();
  }

  @Test
  @DisplayName("취소 뒤 잘못된 다음 대상에도 FAILED 페이지를 내지 않는다")
  void cancellationStopsInvalidRemainingTarget() {
    // given
    scenario.cancelAfterPage();
    // when
    var result = scenario.collect("file:F1", "file:");
    // then
    assertThat(result.acls()).containsOnlyKeys("file:F1");
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure -> assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
  }

  @Test
  @DisplayName("마지막 ACL 페이지 뒤 취소되어도 정상 완료로 반환하지 않는다")
  void cancellationAfterLastPageIsNotComplete() {
    // given
    scenario.cancelAfterPage();
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure -> assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
  }

  @Test
  @DisplayName("시작 전에 취소하면 잘못된 설정보다 취소를 먼저 반환한다")
  void cancellationPrecedesConfigurationFailure() {
    // given
    scenario.cancelBeforeCollect();
    scenario.visibility("null");
    // when
    var result = scenario.collect("file:F1");
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure -> assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
  }

  private static RawAcl ok(String... keys) {
    Set<AccessKey> values = new HashSet<>();
    for (String key : keys) values.add(AccessKey.parse(key));
    return RawAcl.observed(DocumentAcl.Status.OK, values, DriveAccessScenario.NOW);
  }

  private static RawAcl failed() {
    return RawAcl.observed(DocumentAcl.Status.FAILED, Set.of(), DriveAccessScenario.NOW);
  }
}
