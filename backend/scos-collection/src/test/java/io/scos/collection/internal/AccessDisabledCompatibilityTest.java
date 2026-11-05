package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.projection.ProjectionRun;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@DisplayName("접근 수집 비활성은 기존 본문 계약을 보존한다 (T-b19·F12·F15·F16·F17)")
class AccessDisabledCompatibilityTest {
  @Test
  @DisplayName("Slack은 기존 경로·순서·건수로만 요청한다")
  void disabledAccessPreservesSlackRequests() throws Exception {
    try (var s = AccessDisabledScenario.slack()) {
      // when
      s.collect();
      // then
      assertThat(s.slackRequests())
          .containsExactly(
              "/api/users.conversations?types=public_channel&exclude_archived=true&limit=200",
              "/api/conversations.history?channel=C1");
    }
  }

  @Test
  @DisplayName("Drive는 TS와 같은 본문 경로·순서·건수로만 요청한다")
  void disabledAccessPreservesDriveRequests() throws Exception {
    try (var s = AccessDisabledScenario.drive()) {
      // when
      s.collect();
      // then
      assertThat(s.requests())
          .containsExactlyElementsOf(AccessDisabledScenario.expectedRequests("google-drive"));
    }
  }

  @Test
  @DisplayName("Jira는 같은 요청 집합으로 본문 전체를 재수집한다")
  void disabledAccessPreservesJiraRecrawl() throws Exception {
    try (var s = AccessDisabledScenario.jira()) {
      // given
      s.collect();
      var before = s.versions();
      int first = s.requests().size();
      // when
      s.collect();
      // then
      assertThat(s.requests().subList(0, first))
          .containsExactlyInAnyOrderElementsOf(AccessDisabledScenario.expectedRequests("jira"));
      assertThat(
              s.requests().subList(first, s.requests().size()).stream()
                  .filter(r -> r.path().matches("/rest/api/3/issue/[^/]+")))
          .containsExactlyInAnyOrderElementsOf(
              AccessDisabledScenario.expectedRequests("jira").stream()
                  .filter(r -> r.path().matches("/rest/api/3/issue/[^/]+"))
                  .toList());
      assertThat(s.versions()).containsExactlyElementsOf(before);
    }
  }

  @Test
  @DisplayName("Jira 병렬 창 사이의 필드·프로젝트·이슈 발견 순서는 유지된다")
  void disabledJiraKeepsSequentialDiscoveryOrder() throws Exception {
    try (var s = AccessDisabledScenario.jira()) {
      // when
      s.collect();
      // then
      assertThat(
              s.requests().stream()
                  .map(AccessDisabledScenario.Request::path)
                  .filter(
                      path ->
                          path.equals("/rest/api/3/field")
                              || path.endsWith("/search")
                              || path.endsWith("/search/jql")))
          .containsExactly(
              "/rest/api/3/field",
              "/rest/api/3/field",
              "/rest/api/3/project/search",
              "/rest/api/3/search/jql",
              "/rest/api/3/search/jql",
              "/rest/api/3/project/search",
              "/rest/api/3/search/jql",
              "/rest/api/3/filter/search");
    }
  }

  static Stream<Arguments> goldenSources() {
    Supplier<AccessDisabledScenario> drive = AccessDisabledScenario::drive;
    Supplier<AccessDisabledScenario> jira = AccessDisabledScenario::jira;
    return Stream.of(Arguments.of("google-drive", drive), Arguments.of("jira", jira));
  }

  @ParameterizedTest
  @MethodSource("goldenSources")
  @DisplayName("접근 비활성의 확인점은 기존 TS 골든과 같다")
  void disabledAccessPreservesCheckpoint(String kind, Supplier<AccessDisabledScenario> scenario)
      throws Exception {
    try (var s = scenario.get()) {
      // when
      s.collect();
      // then
      assertThat(s.checkpoint()).isEqualTo(AccessDisabledScenario.expected(kind).get("checkpoint"));
    }
  }

  @ParameterizedTest
  @MethodSource("goldenSources")
  @DisplayName("접근 비활성의 원본 버전은 기존 TS 골든과 같다")
  void disabledAccessPreservesVersions(String kind, Supplier<AccessDisabledScenario> scenario)
      throws Exception {
    try (var s = scenario.get()) {
      // when
      s.collect();
      // then
      assertThat(s.versions())
          .containsExactlyInAnyOrderElementsOf(AccessDisabledScenario.expectedVersions(kind));
    }
  }

  @Test
  @DisplayName("정상 본문 run은 네 접근 저장소에 관측을 만들지 않는다")
  void disabledAccessCreatesNoAclObservations() throws Exception {
    try (var s = AccessDisabledScenario.slack()) {
      // given
      s.requireNoAccessWrites();
      // when
      s.collect();
      // then
      assertThat(s.accessWrites())
          .isEqualTo(new AccessDisabledScenario.AccessWrites(0, 0, 0, 0, 0));
    }
  }

  @Test
  @DisplayName("실제 본문 투영은 ACL 후보·행·전용 트랜잭션을 만들지 않는다")
  void disabledProjectionCreatesNoDocumentAcl() throws Exception {
    try (var s = AccessDisabledScenario.slack()) {
      // given
      s.collect();
      // when
      var report = s.project();
      // then: 소유 배치 1 + 본문 저장 1 + 소유 skip 보고 1 (fix3)
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
      assertThat(s.projected()).isEqualTo(new AccessDisabledScenario.Projected(1, 0, 0, 3));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Notion UNSUPPORTED는 수집 설정과 무관하게 권한 HTTP가 없다")
  void unsupportedNotionUsesNoPermissionCalls(boolean collectAccess) throws Exception {
    try (var s =
        collectAccess
            ? AccessDisabledScenario.notionWithAccessCollection()
            : AccessDisabledScenario.notion()) {
      // when
      s.collect();
      // then
      assertThat(s.requests())
          .containsExactly(
              new AccessDisabledScenario.Request("/v1/pages/root-wiki", Map.of()),
              new AccessDisabledScenario.Request(
                  "/v1/blocks/root-wiki/children", Map.of("page_size", "100")),
              new AccessDisabledScenario.Request("/v1/search", Map.of()),
              new AccessDisabledScenario.Request("/v1/search", Map.of()));
    }
  }

  @Test
  @DisplayName("Slack 확인점은 비활성 모드에서 유지된다")
  void disabledSlackKeepsCheckpoint() throws Exception {
    try (var s = AccessDisabledScenario.slack()) {
      // when
      s.collect();
      // then
      assertThat(s.checkpoint().get("checkpoint"))
          .isEqualTo(
              new ObjectMapper()
                  .readTree(
                      "{\"schemaVersion\":2,\"value\":{\"channelWatermarks\":{\"C1\":\"1760400000.000100\"}}}"));
    }
  }

  @Test
  @DisplayName("Slack 메시지 버전은 비활성 모드에서 유지된다")
  void disabledSlackKeepsVersion() throws Exception {
    try (var s = AccessDisabledScenario.slack()) {
      // when
      s.collect();
      // then
      assertThat(s.versions())
          .containsExactly(
              new AccessDisabledScenario.Version("C1:1760400000.000100", "1760400000.000100"));
    }
  }

  @Test
  @DisplayName("Notion은 F17 v3 확인점을 유지한다")
  void unsupportedNotionKeepsCheckpoint() throws Exception {
    try (var s = AccessDisabledScenario.notion()) {
      // when
      s.collect();
      // then
      assertThat(s.checkpoint().get("checkpoint"))
          .isEqualTo(
              new ObjectMapper()
                  .readTree(
                      """
          {"schemaVersion":3,"value":{"mode":"roots","rootPageIds":["root-wiki"],
           "discoverSharedPages":true,"stage":"traversal",
           "pendingWork":[{"kind":"page","pageId":"root-wiki"}]}}
          """));
    }
  }

  @Test
  @DisplayName("Notion은 편집 시각 버전을 유지한다")
  void unsupportedNotionKeepsVersion() throws Exception {
    try (var s = AccessDisabledScenario.notion()) {
      // when
      s.collect();
      // then
      assertThat(s.versions())
          .containsExactlyInAnyOrder(
              new AccessDisabledScenario.Version("root-wiki", "2026-10-01T09:00:00.000Z"),
              new AccessDisabledScenario.Version(
                  "block:blk-wiki-heading", "2026-08-02T00:00:00.000Z"));
    }
  }

  @Test
  @Tag("db")
  @DisplayName("실제 DB 본문 수집·투영은 다섯 접근 표에 신규 쓰기가 없다")
  void disabledDatabaseWritesNoAccessRows() throws Exception {
    try (var s = new AccessDisabledPostgresScenario()) {
      // when
      var result = s.collectAndProject();
      // then
      assertThat(result)
          .isEqualTo(
              new AccessDisabledPostgresScenario.CollectedProjection(
                  ProjectionRun.Outcome.SUCCEEDED, 1, List.of()));
    }
  }

  @Test
  @Tag("db")
  @DisplayName("감사 어휘는 썼다가 지운 ACL도 감지한다")
  void auditDetectsTransientAclWrite() throws Exception {
    try (var s = new AccessDisabledPostgresScenario()) {
      // when
      s.writeThenDeleteAcl();
      // then
      assertThat(s.writes()).containsExactly("document_acl");
    }
  }

  @Test
  @Tag("db")
  @DisplayName("중단된 감사의 DB 객체가 남아 있어도 다음 감사는 독립적으로 시작한다")
  void auditRecoversFromInterruptedSetup() throws Exception {
    // given
    AccessDisabledPostgresScenario.interruptAuditSetup();
    // when: 이전 감사의 DB 객체를 남긴 채 새 시나리오를 시작한다
    try (var s = new AccessDisabledPostgresScenario()) {
      s.writeThenDeleteAcl();
      // then
      assertThat(s.writes()).containsExactly("document_acl");
    }
  }
}
