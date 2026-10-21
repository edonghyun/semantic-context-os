package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.memory.MemorySourceChangeLog;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 유지보수 한 번 = blob GC(F9) + 변경 로그 보존(F10)을 같은 유예로. DRY_RUN은 둘 다 지우지 않는다. */
@DisplayName("유지보수는 blob GC와 변경 로그 보존을 같은 유예로 돈다(F9·F10)")
class MaintenanceTest {
  static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final GracePeriod GRACE = GracePeriod.ofDays(30);

  /** 기록용 blob 포트가 지웠다고 답하는 수(보고의 deleted는 long). */
  static final long BLOBS_DELETED = BlobGarbageCollectorTest.RecordingPort.DELETED;

  BlobGarbageCollectorTest.RecordingPort blobs = new BlobGarbageCollectorTest.RecordingPort();
  MemorySourceChangeLog changes = new MemorySourceChangeLog();
  Maintenance maintenance =
      new Maintenance(new BlobGarbageCollector(blobs, Clock.fixed(NOW, ZoneOffset.UTC)), changes);

  /** 유예(30일)보다 오래된 변경 2건(40일·31일 전) 뒤에 유예 안의 변경 1건(29일 전). */
  void givenTwoExpiredChangesAndOneRecent() {
    for (int daysAgo : List.of(40, 31, 29)) change(NOW.minus(Duration.ofDays(daysAgo)));
  }

  void change(Instant at) {
    changes.appendAll(
        List.of(
            new SourceChangeLog.Entry(
                SOURCE,
                Optional.of("x"),
                SourceChangeLog.Kind.UPSERT,
                Optional.of("v1"),
                Optional.empty(),
                at)));
  }

  @Test
  @DisplayName("DELETE 보고에는 blob 정리 결과가 담긴다")
  void sweepsBlobs() {
    // when
    Maintenance.Report report = maintenance.run(GRACE, SweepMode.DELETE);

    // then
    assertThat(report.blobs())
        .extracting(BlobGarbageCollector.Report::mode, BlobGarbageCollector.Report::deleted)
        .containsExactly(SweepMode.DELETE, BLOBS_DELETED);
  }

  @Test
  @DisplayName("DRY_RUN은 blob을 지우지 않는다")
  void dryRunKeepsBlobs() {
    // when
    maintenance.run(GRACE, SweepMode.DRY_RUN);

    // then
    assertThat(blobs.deleteCutoffs).isEmpty();
  }

  @Test
  @DisplayName("DRY_RUN 보고의 blob 모드는 DRY_RUN이다")
  void dryRunReportsMode() {
    // when
    Maintenance.Report report = maintenance.run(GRACE, SweepMode.DRY_RUN);

    // then
    assertThat(report.blobs().mode()).isEqualTo(SweepMode.DRY_RUN);
  }

  @Nested
  @DisplayName("유예보다 오래된 변경 2건 뒤에 유예 안의 변경 1건이 있을 때")
  class ExpiredAndRecentChanges {
    @BeforeEach
    void givenChanges() {
      // seq 1(40일 전)·2(31일 전)·3(29일 전)
      givenTwoExpiredChangesAndOneRecent();
    }

    @Test
    @DisplayName("DELETE는 유예보다 오래된 변경만 지운다")
    void prunesOldChanges() {
      // when
      maintenance.run(GRACE, SweepMode.DELETE);

      // then
      assertThat(changes.all()).extracting(SourceChangeLog.Change::seq).containsExactly(3L);
    }

    @Test
    @DisplayName("DELETE 보고에는 지운 변경 수가 담긴다")
    void reportsPrunedChanges() {
      // when
      Maintenance.Report report = maintenance.run(GRACE, SweepMode.DELETE);

      // then
      assertThat(report.prunedChanges()).isEqualTo(2);
    }

    @Test
    @DisplayName("변경을 지워도 head는 줄지 않는다")
    void pruningKeepsHead() {
      // when
      maintenance.run(GRACE, SweepMode.DELETE);

      // then
      assertThat(changes.head()).isEqualTo(3);
    }

    @Test
    @DisplayName("DRY_RUN은 변경 로그를 지우지 않는다")
    void dryRunKeepsChanges() {
      // when
      maintenance.run(GRACE, SweepMode.DRY_RUN);

      // then
      assertThat(changes.read(Optional.empty(), 0, 10).changes()).hasSize(3);
    }

    @Test
    @DisplayName("DRY_RUN 보고의 지운 변경 수는 0이다")
    void dryRunReportsNoPrunedChanges() {
      // when
      Maintenance.Report report = maintenance.run(GRACE, SweepMode.DRY_RUN);

      // then
      assertThat(report.prunedChanges()).isZero();
    }
  }
}
