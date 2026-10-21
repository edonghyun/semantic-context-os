package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.SupervisorScenario.Collect;
import io.scos.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 감독의 운영 이벤트 로그(TS source-supervisor.ts — 데몬 JSON 로그): 스냅샷 모드로 시도하는 소스는 info
 * `collection.run.snapshot`, run이 되기 전에 실패한 소스는 error `supervisor.source.error`(detail.message)다.
 */
@DisplayName("감독의 운영 이벤트 로그 — 스냅샷 시도는 info, run 전 실패는 error(TS)")
// 제품 클래스의 공용 로거에 appender를 단다 — 같은 JVM의 다른 테스트가 남긴 줄이 섞이지 않게 혼자 돈다(TESTING §6.3)
@Isolated
class SupervisorEventLogTest {
  static final ObjectMapper JSON = new ObjectMapper();

  final SupervisorScenario supervisor = new SupervisorScenario();
  final LogCapture log = LogCapture.of(SourceSupervisor.class);

  @AfterEach
  void close() {
    log.close();
    supervisor.close();
  }

  @Test
  @DisplayName("스냅샷 모드로 시도하는 소스마다 info collection.run.snapshot을 남긴다")
  void logsSnapshotAttempt() {
    // given
    supervisor.snapshotEvery(SupervisorScenario.SNAPSHOT_INTERVAL).givenSource("notion-main");

    // when
    supervisor.runCycle();

    // then
    assertThat(log.events("collection.run.snapshot"))
        .singleElement()
        .isEqualTo(
            JSON.readTree(
                "{\"level\":\"info\",\"event\":\"collection.run.snapshot\",\"sourceInstanceId\":\"notion-main\"}"));
  }

  @Test
  @DisplayName("run 전에 실패한 소스는 error supervisor.source.error를 남긴다")
  void logsSourceError() {
    // given: 수집이 INJECTED("boom")를 던진다
    supervisor.givenSource("jira-main", Collect.THROWS);

    // when
    supervisor.runCycle();

    // then
    JsonNode line = log.events("supervisor.source.error").getFirst();
    assertThat(line.get("level").asString()).isEqualTo("error");
    assertThat(line.get("detail")).isEqualTo(JSON.readTree("{\"message\":\"boom\"}"));
  }

  @Test
  @DisplayName("메시지 없는 실패는 TS 대체 문구로 남긴다")
  void messageFallsBackToTsPhrase() {
    // when
    String message = SourceSupervisor.errorMessage(new IllegalStateException());

    // then
    assertThat(message).isEqualTo("Source collection failed");
  }
}
