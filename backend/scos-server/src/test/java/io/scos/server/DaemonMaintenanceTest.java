package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.scos.collection.internal.BlobGarbageCollector;
import io.scos.collection.internal.Maintenance;
import io.scos.collection.internal.SweepMode;
import io.scos.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.ObjectMapper;

/**
 * 데몬의 정비 한 번(TS source-service-runner.runMaintenanceIfDue): 끝나면 info
 * `maintenance.sweep.completed`(blob 정리 수치), 실패하면 error `maintenance.sweep.failed`(메시지)를 남기고 던지지
 * 않는다 — 다음 예정 시각은 그대로 전진해 매 사이클 재시도 폭주를 막는다.
 */
@DisplayName("데몬 정비 한 번은 결과를 로그로 남기고 실패를 던지지 않는다(TS runMaintenanceIfDue)")
// 제품 클래스의 공용 로거에 appender를 단다 — 같은 JVM의 다른 테스트가 남긴 줄이 섞이지 않게 혼자 돈다(TESTING §6.3)
@Isolated
class DaemonMaintenanceTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Maintenance.Report SWEPT =
      new Maintenance.Report(
          new BlobGarbageCollector.Report(120, 3, 110, 7, 1_048_576, SweepMode.DELETE), 4);

  final LogCapture log = LogCapture.of(DaemonMaintenance.class);

  @AfterEach
  void detach() {
    log.close();
  }

  static DaemonMaintenance failingWith(String message) {
    return new DaemonMaintenance(
        () -> {
          throw new IllegalStateException(message);
        });
  }

  @Test
  @DisplayName("정비가 끝나면 info maintenance.sweep.completed에 정리 수치를 남긴다")
  void logsCompletedSweep() {
    // when
    new DaemonMaintenance(() -> SWEPT).run();

    // then
    assertThat(log.events("maintenance.sweep.completed"))
        .singleElement()
        .isEqualTo(
            JSON.readTree(
                "{\"level\":\"info\",\"event\":\"maintenance.sweep.completed\",\"detail\":{\"blob\":"
                    + "{\"scanned\":120,\"deleted\":3,\"retainedByReference\":110,\"retainedByGrace\":7,"
                    + "\"bytesFreed\":1048576},\"changesPruned\":4}}"));
  }

  @Test
  @DisplayName("정비가 실패해도 던지지 않는다")
  void failureDoesNotThrow() {
    // given
    DaemonMaintenance failing = failingWith("blob store unavailable");

    // when·then
    assertThatCode(failing::run).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("정비가 실패하면 error maintenance.sweep.failed를 메시지와 함께 남긴다")
  void logsFailedSweep() {
    // when
    failingWith("blob store unavailable").run();

    // then
    assertThat(log.events("maintenance.sweep.failed"))
        .singleElement()
        .extracting(line -> line.get("level").asString(), line -> line.get("detail"))
        .containsExactly("error", JSON.readTree("{\"message\":\"blob store unavailable\"}"));
  }
}
