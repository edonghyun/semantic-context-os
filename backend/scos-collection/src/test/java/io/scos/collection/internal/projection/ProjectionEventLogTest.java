package io.scos.collection.internal.projection;

import static io.scos.collection.internal.projection.ProjectionScenario.SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.support.LogCapture;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.ObjectMapper;

/**
 * 투영 요청의 연속 실패 신호(ADR-0018 §11): 연속 실패 수가 maxAttempts(2)에 이르면 error
 * `projection.request.failed_repeatedly`를 남긴다. 그 전에는 남기지 않는다.
 */
@Tag("db")
@DisplayName("투영 요청이 maxAttempts만큼 잇달아 실패하면 이벤트로 남긴다")
// 제품 클래스의 공용 로거에 appender를 단다 — 같은 JVM의 다른 테스트가 남긴 줄이 섞이지 않게 혼자 돈다(TESTING §6.3)
@Isolated
class ProjectionEventLogTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Duration PAST_BACKOFF = Duration.ofMinutes(10);

  final ProjectionScenario projection = new ProjectionScenario();
  final LogCapture log = LogCapture.of(ProjectionRun.class);

  @AfterEach
  void close() {
    log.close();
    projection.close();
  }

  @Test
  @DisplayName("첫 실패는 연속 실패 이벤트를 남기지 않는다")
  void firstFailureIsQuiet() {
    // given
    projection.collect();
    projection.embedderFails();

    // when
    projection.runDue();

    // then
    assertThat(log.events("projection.request.failed_repeatedly")).isEmpty();
  }

  @Test
  @DisplayName("둘째 연속 실패는 error에 소스·연속 실패 수·시도 수·parked·오류 요약을 남긴다")
  void logsRepeatedFailure() {
    // given
    projection.collect();
    projection.embedderFails();
    projection.runDue();
    projection.advanceClock(PAST_BACKOFF);

    // when
    projection.runDue();

    // then
    assertThat(log.events("projection.request.failed_repeatedly"))
        .singleElement()
        .isEqualTo(
            JSON.readTree(
                "{\"level\":\"error\",\"event\":\"projection.request.failed_repeatedly\","
                    + "\"sourceInstanceId\":\""
                    + SOURCE.value()
                    + "\",\"detail\":{\"consecutiveFailures\":2,\"attempts\":2,\"parked\":true,"
                    + "\"errorSummary\":\"3 document ingestion(s) failed\"}}"));
  }
}
