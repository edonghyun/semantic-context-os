package io.scos.server;

import static io.scos.server.DaemonScenario.SupervisorStorage.FAILS;
import static io.scos.server.DaemonScenario.SupervisorStorage.FAILS_FATALLY;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.support.LogCapture;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.ObjectMapper;

/** 데몬 루프의 오류 이벤트: 사이클 실패, 루프 사망, 정지 상한 초과. 이벤트는 이름·수준·detail·원인 예외로 남는다(logging.md §9). */
@DisplayName("상주 데몬은 사이클 실패·루프 사망·정지 상한 초과를 이벤트로 남긴다")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
// 제품 클래스의 공용 로거에 appender를 단다 — 같은 JVM의 다른 테스트가 남긴 줄이 섞이지 않게 혼자 돈다(TESTING §6.3)
@Isolated
class CollectionDaemonEventLogTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Duration SHORT_SHUTDOWN = Duration.ofMillis(300);

  final DaemonScenario scenario = new DaemonScenario();
  final LogCapture log = LogCapture.of(CollectionDaemon.class);

  @AfterEach
  void tearDown() {
    log.close();
    scenario.close();
  }

  @Test
  @DisplayName("감독 사이클이 예외로 끝나면 error daemon.cycle.failed에 메시지와 원인을 남긴다")
  void logsCycleFailure() throws Exception {
    // given
    scenario.supervisorStorage(FAILS);

    // when
    scenario.start();
    scenario.nextCycle();

    // then
    assertThat(log.events("daemon.cycle.failed"))
        .first()
        .extracting(
            line -> line.get("level").asString(),
            line -> line.get("detail"),
            line -> line.at("/error/type").asString())
        .containsExactly(
            "error",
            JSON.readTree("{\"message\":\"storage down\"}"),
            "java.lang.IllegalStateException");
  }

  @Test
  @DisplayName("루프가 치명 오류로 끝나면 error daemon.loop.died에 종료 코드와 원인을 남긴다")
  void logsLoopDeath() throws Exception {
    // given
    scenario.supervisorStorage(FAILS_FATALLY);

    // when
    scenario.start();
    scenario.awaitLoopEnded();

    // then
    assertThat(log.events("daemon.loop.died"))
        .singleElement()
        .extracting(
            line -> line.get("level").asString(),
            line -> line.get("detail"),
            line -> line.at("/error/type").asString())
        .containsExactly("error", JSON.readTree("{\"exitCode\":70}"), "java.lang.LinkageError");
  }

  @Test
  @DisplayName("정지가 상한 안에 끝나지 않으면 warn daemon.stop.timed_out에 상한을 남긴다")
  void logsStopTimeout() throws Exception {
    // given
    scenario.givenSource("a");
    scenario.observerHoldsCycle().shutdownTimeout(SHORT_SHUTDOWN);
    scenario.start();
    scenario.awaitFirstCycle();

    // when
    scenario.stop();

    // then
    assertThat(log.events("daemon.stop.timed_out"))
        .singleElement()
        .isEqualTo(
            JSON.readTree(
                "{\"level\":\"warn\",\"event\":\"daemon.stop.timed_out\","
                    + "\"detail\":{\"shutdownTimeoutMs\":300}}"));
  }
}
