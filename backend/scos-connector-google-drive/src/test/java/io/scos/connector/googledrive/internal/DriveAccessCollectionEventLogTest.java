package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.support.LogCapture;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Drive 접근 비용은 파일·사용자 label 없이 run 관측 로그에 집계된다(T-b15)")
class DriveAccessCollectionEventLogTest {
  private final DriveAccessScenario scenario = new DriveAccessScenario();
  private final LogCapture logs = LogCapture.of(DriveAccessCollection.class);

  @AfterEach
  void close() {
    logs.close();
    scenario.close();
  }

  @Test
  @DisplayName("페이지·재시도를 포함한 permission 호출 수와 전체 대상 수·시간을 기록한다")
  void pagesAndRetriesAreIncludedInAccessCost() {
    // given
    scenario.retryThenPages();
    // when
    var outcome = scenario.collect("file:F1", "drive:D1", "file:F2");
    // then
    assertThat(outcome.failure()).isEmpty();
    assertThat(logs.events("collection.drive_access.finished"))
        .singleElement()
        .satisfies(
            event ->
                assertThat(
                        List.of(
                            event.path("level").asString(),
                            event.path("sourceInstanceId").asString(),
                            event.path("detail").toString()))
                    .containsExactly(
                        "info",
                        "drive-a",
                        "{\"targetCount\":3,\"permissionCalls\":5,\"elapsedMs\":50}"));
  }

  @Test
  @DisplayName("대상 실패 뒤에도 후속 파일을 관측하고 비용을 기록한다")
  void failureStillReportsAllAttemptedTargets() {
    // given
    scenario.forbidden("F1", 404);
    scenario.permissions("F2");
    // when
    var outcome = scenario.collect("file:F1", "file:F2");
    // then
    assertThat(outcome.failure()).isPresent();
    assertThat(logs.events("collection.drive_access.finished"))
        .singleElement()
        .satisfies(
            event ->
                assertThat(event.path("detail").toString())
                    .isEqualTo("{\"targetCount\":2,\"permissionCalls\":2,\"elapsedMs\":0}"));
  }

  @Test
  @DisplayName("빈 대상 run은 이전 run의 비용을 이어받지 않는다")
  void emptyRunDoesNotReusePreviousCost() {
    // given
    scenario.collect("file:F1");
    // when
    scenario.collect();
    // then
    assertThat(logs.events("collection.drive_access.finished"))
        .hasSize(2)
        .last()
        .satisfies(
            event ->
                assertThat(event.path("detail").toString())
                    .isEqualTo("{\"targetCount\":0,\"permissionCalls\":0,\"elapsedMs\":0}"));
  }

  @Test
  @DisplayName("OAuth 재시도는 실제 permission GET 호출 수에 더하지 않는다")
  void tokenRetryIsNotAPermissionCall() {
    // given
    scenario.retryTokenOnce();
    // when
    var outcome = scenario.collect("file:F1");
    // then
    assertThat(outcome.failure()).isEmpty();
    assertThat(logs.events("collection.drive_access.finished"))
        .singleElement()
        .satisfies(
            event -> assertThat(event.path("detail").path("permissionCalls").asInt()).isEqualTo(1));
  }

  @Test
  @DisplayName("OAuth 거부로 permission GET을 시작하지 못하면 호출 수는 0이다")
  void tokenFailureIsNotAPermissionCall() {
    // given
    scenario.rejectToken();
    // when
    var outcome = scenario.collect("file:F1");
    // then
    assertThat(outcome.failure()).isPresent();
    assertThat(logs.events("collection.drive_access.finished"))
        .singleElement()
        .satisfies(
            event -> assertThat(event.path("detail").path("permissionCalls").asInt()).isZero());
  }

  @Test
  @DisplayName("헤더 검증 거부로 permission GET을 시작하지 못하면 호출 수는 0이다")
  void invalidHeaderIsNotAPermissionCall() {
    // given
    scenario.invalidTokenHeader();
    // when
    var outcome = scenario.collect("file:F1");
    // then
    assertThat(outcome.failure()).isPresent();
    assertThat(logs.events("collection.drive_access.finished"))
        .singleElement()
        .satisfies(
            event -> assertThat(event.path("detail").path("permissionCalls").asInt()).isZero());
  }
}
