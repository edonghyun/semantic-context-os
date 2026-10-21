package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * F9 정책: cutoff = now − 유예, 보고 수치는 포트 집계 그대로, DRY_RUN은 삭제를 부르지 않는다. SQL 의미는
 * PostgresBlobMaintenanceTest.
 */
@DisplayName("blob 정리 정책 — cutoff는 now − 유예, 보고는 포트 집계(F9)")
class BlobGarbageCollectorTest {
  static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  static final GracePeriod GRACE = GracePeriod.ofDays(30);
  static final Instant CUTOFF = Instant.parse("2026-09-14T00:00:00Z");

  /** 집계를 돌려주고 호출을 기록하는 포트 대역. 집계의 deletable과 실제 삭제 수를 일부러 다르게 둔다. */
  static final class RecordingPort implements BlobMaintenancePort {
    static final BlobStats STATS = new BlobStats(10, 6, 1, 4, 3_000);
    static final int DELETED = 3;

    final List<Instant> statsCutoffs = new ArrayList<>();
    final List<Instant> deleteCutoffs = new ArrayList<>();

    @Override
    public BlobStats stats(Instant cutoff) {
      statsCutoffs.add(cutoff);
      return STATS;
    }

    @Override
    public int deleteUnreferencedBefore(Instant cutoff) {
      deleteCutoffs.add(cutoff);
      return DELETED;
    }
  }

  RecordingPort port = new RecordingPort();
  BlobGarbageCollector gc = new BlobGarbageCollector(port, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("유예가 0일이면 cutoff는 now다")
  void zeroGraceCutsAtNow() {
    // when
    gc.sweep(GracePeriod.ofDays(0), SweepMode.DRY_RUN);

    // then
    assertThat(port.statsCutoffs).containsExactly(NOW);
  }

  @Nested
  @DisplayName("DELETE로 sweep하면")
  class Delete {
    @Test
    @DisplayName("now − 유예를 cutoff로 집계한다")
    void statsWithGraceCutoff() {
      // when
      gc.sweep(GRACE, SweepMode.DELETE);

      // then
      assertThat(port.statsCutoffs).containsExactly(CUTOFF);
    }

    @Test
    @DisplayName("집계와 같은 cutoff로 삭제한다")
    void deletesWithGraceCutoff() {
      // when
      gc.sweep(GRACE, SweepMode.DELETE);

      // then
      assertThat(port.deleteCutoffs).containsExactly(CUTOFF);
    }

    @Test
    @DisplayName("보고는 포트 집계와 실제 삭제 수다")
    void deleteReportsActualDeletes() {
      // when
      BlobGarbageCollector.Report report = gc.sweep(GRACE, SweepMode.DELETE);

      // then: 집계(10, 참조 6, 유예 1, 지울 수 4, 3,000바이트) 중 deleted만 실제 삭제 수 3이다
      assertThat(report)
          .isEqualTo(new BlobGarbageCollector.Report(10, 3, 6, 1, 3_000, SweepMode.DELETE));
    }
  }

  @Nested
  @DisplayName("DRY_RUN으로 sweep하면")
  class DryRun {
    @Test
    @DisplayName("삭제를 부르지 않는다")
    void dryRunDoesNotDelete() {
      // when
      gc.sweep(GRACE, SweepMode.DRY_RUN);

      // then
      assertThat(port.deleteCutoffs).isEmpty();
    }

    @Test
    @DisplayName("지울 수(deletable)를 deleted로 보고한다")
    void dryRunReportsDeletable() {
      // when
      BlobGarbageCollector.Report report = gc.sweep(GRACE, SweepMode.DRY_RUN);

      // then: 집계(10, 참조 6, 유예 1, 지울 수 4, 3,000바이트) 그대로, deleted는 지울 수 4다
      assertThat(report)
          .isEqualTo(new BlobGarbageCollector.Report(10, 4, 6, 1, 3_000, SweepMode.DRY_RUN));
    }
  }
}
