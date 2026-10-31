package io.scos.server;

import static io.scos.server.DaemonObserver.ATTEMPTS;
import static io.scos.server.DaemonObserver.CYCLE;
import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.Reports;
import io.scos.collection.internal.SourceSupervisor.Attempt;
import io.scos.collection.internal.SourceSupervisor.AttemptOutcome;
import io.scos.collection.internal.SourceSupervisor.Cycle;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.LogCapture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** 사이클 결과 → 마지막 상태(health)와 사이클 지표, 사이클 요약 이벤트. 데몬 루프 없이 결과를 직접 기록한다. */
@DisplayName("데몬 관측자는 사이클 결과를 마지막 상태와 사이클·시도 지표로 남긴다")
// 제품 클래스의 공용 로거에 appender를 달고 수준을 debug로 연다 — 혼자 돈다(TESTING §6.3)
@Isolated
class DaemonObserverTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Instant STARTED = Instant.parse("2026-10-14T00:00:00Z");
  static final Instant FINISHED = STARTED.plusSeconds(3);

  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final DaemonObserver observer = new DaemonObserver(registry);

  @Test
  @DisplayName("기록한 사이클 결과를 마지막 사이클로 기억한다")
  void remembersLastCycle() {
    // given
    CollectionDaemon.CycleOutcome outcome = cycleWith(succeeded("a"));

    // when
    observer.record(outcome);

    // then
    assertThat(observer.last()).containsSame(outcome);
  }

  @Test
  @DisplayName("수집이 있는 사이클마다 사이클 타이머를 한 번 기록한다")
  void timesEachCycle() {
    // when
    observer.record(cycleWith(succeeded("a")));

    // then
    assertThatMetrics(registry).hasTimerCount(CYCLE, 1);
  }

  @Test
  @DisplayName("성공한 시도를 ok로 센다")
  void countsSuccesses() {
    // when
    observer.record(cycleWith(succeeded("a")));

    // then
    assertThatMetrics(registry).hasCounter(ATTEMPTS, "outcome", "ok", 1);
  }

  @Test
  @DisplayName("성공한 시도만 있으면 failed 카운터를 만들지 않는다")
  void countsOnlySuccesses() {
    // when
    observer.record(cycleWith(succeeded("a")));

    // then
    assertThatMetrics(registry).hasNoCounter(ATTEMPTS, "outcome", "failed");
  }

  @Test
  @DisplayName("실패한 시도를 failed로 센다")
  void countsFailures() {
    // when
    observer.record(cycleWith(failed("a"), failed("b")));

    // then
    assertThatMetrics(registry).hasCounter(ATTEMPTS, "outcome", "failed", 2);
  }

  @Test
  @DisplayName("시도가 전부 실패하면 ok 카운터를 만들지 않는다")
  void countsOnlyFailures() {
    // when
    observer.record(cycleWith(failed("a"), failed("b")));

    // then
    assertThatMetrics(registry).hasNoCounter(ATTEMPTS, "outcome", "ok");
  }

  @Test
  @DisplayName("사이클마다 성공과 실패를 각각 더한다")
  void accumulatesAcrossCycles() {
    // given
    observer.record(cycleWith(failed("a")));

    // when
    observer.record(cycleWith(succeeded("a")));

    // then
    assertThatMetrics(registry)
        .hasCounter(ATTEMPTS, "outcome", "failed", 1)
        .hasCounter(ATTEMPTS, "outcome", "ok", 1);
  }

  @Test
  @DisplayName("수집 없이 오류로 끝난 사이클은 타이머·시도 지표를 남기지 않는다")
  void failedCycleLeavesNoMetrics() {
    // when
    observer.record(failedCycle());

    // then
    assertThatMetrics(registry).hasNoMeter(CYCLE).hasNoMeter(ATTEMPTS);
  }

  @Nested
  @DisplayName("사이클 요약 이벤트 daemon.cycle.completed는")
  class CycleSummary {
    final LogCapture log = LogCapture.of(DaemonObserver.class);
    final Logger logger = (Logger) LoggerFactory.getLogger(DaemonObserver.class);
    final Level before = logger.getLevel();

    @BeforeEach
    void openDebug() {
      logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restore() {
      logger.setLevel(before);
      log.close();
    }

    @Test
    @DisplayName("첫 사이클은 info로 숫자를 남긴다")
    void firstCycleIsInfo() {
      // when
      observer.record(cycleWith(succeeded("a")));

      // then
      assertThat(log.events("daemon.cycle.completed"))
          .singleElement()
          .isEqualTo(
              JSON.readTree(
                  "{\"level\":\"info\",\"event\":\"daemon.cycle.completed\",\"detail\":"
                      + "{\"attempts\":1,\"failed\":0,\"deferred\":0,\"projections\":0}}"));
    }

    @Test
    @DisplayName("숫자가 바로 앞 사이클과 같고 실패가 없으면 debug다")
    void unchangedCycleIsDebug() {
      // given
      observer.record(cycleWith(succeeded("a")));

      // when
      observer.record(cycleWith(succeeded("b")));

      // then
      assertThat(log.events("daemon.cycle.completed"))
          .extracting(line -> line.get("level").asString())
          .containsExactly("info", "debug");
    }

    @Test
    @DisplayName("숫자가 바로 앞 사이클과 다르면 info다")
    void changedCycleIsInfo() {
      // given
      observer.record(cycleWith(succeeded("a")));

      // when
      observer.record(cycleWith(succeeded("a"), succeeded("b")));

      // then
      assertThat(log.events("daemon.cycle.completed"))
          .extracting(line -> line.get("level").asString())
          .containsExactly("info", "info");
    }

    @Test
    @DisplayName("실패한 시도가 있으면 앞 사이클과 같아도 info다")
    void repeatedFailureIsInfo() {
      // given
      observer.record(cycleWith(failed("a")));

      // when
      observer.record(cycleWith(failed("a")));

      // then
      assertThat(log.events("daemon.cycle.completed"))
          .extracting(line -> line.get("level").asString(), line -> line.at("/detail/failed"))
          .containsExactly(tuple("info", JSON.readTree("1")), tuple("info", JSON.readTree("1")));
    }

    @Test
    @DisplayName("사이클 오류가 있으면 앞 사이클과 같아도 info로 오류 타입을 싣는다")
    void repeatedErrorIsInfo() {
      // given
      observer.record(failedCycle());

      // when
      observer.record(failedCycle());

      // then
      assertThat(log.events("daemon.cycle.completed"))
          .extracting(line -> line.get("level").asString(), line -> line.get("detail"))
          .containsOnly(
              tuple(
                  "info",
                  JSON.readTree(
                      "{\"attempts\":0,\"failed\":0,\"deferred\":0,\"projections\":0,"
                          + "\"errorType\":\"IllegalStateException\"}")));
    }
  }

  // ---- 데이터 ----

  static CollectionDaemon.CycleOutcome cycleWith(Attempt... attempts) {
    Cycle cycle = new Cycle(STARTED, FINISHED, List.of(attempts), Map.of());
    return new CollectionDaemon.CycleOutcome(
        FINISHED, Optional.of(cycle), List.of(), Optional.empty());
  }

  static CollectionDaemon.CycleOutcome failedCycle() {
    return new CollectionDaemon.CycleOutcome(
        FINISHED, Optional.empty(), List.of(), Optional.of(new IllegalStateException("down")));
  }

  static Attempt succeeded(String source) {
    SourceInstanceId id = new SourceInstanceId(source);
    return new Attempt(
        id, CollectionMode.INCREMENTAL, new AttemptOutcome.Collected(Reports.succeeded(id)));
  }

  static Attempt failed(String source) {
    return new Attempt(
        new SourceInstanceId(source),
        CollectionMode.INCREMENTAL,
        new AttemptOutcome.Failed(new IllegalStateException("boom")));
  }
}
