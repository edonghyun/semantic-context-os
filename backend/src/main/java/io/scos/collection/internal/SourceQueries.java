package io.scos.collection.internal;

import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 운영 조회 포트(읽기 전용, TS SqliteSourceStore의
 * listSourceInstances·getSourceStatus·listRecentRejections·list·get). 조회 CLI(`source
 * list|status|rejections`, `raw list|get`, `status`)가 쓴다. 순서·집계 의미는 TS와 같다 — 문자열 비교는 코드 유닛
 * 순(COLLATE "C").
 */
public interface SourceQueries {
  /** 모든 소스(꺼진 것 포함)를 id 순으로. */
  List<SourceInstance> listSources();

  /** 소스 상태. 없는 소스는 empty. now는 점유가 살아 있는지 판정하는 시각이다. */
  Optional<SourceStatus> status(SourceInstanceId source, Instant now);

  /** 이 소스의 최근 거부 항목(captured_at 역순, 같으면 나중에 기록된 것 먼저). */
  List<RecentRejection> recentRejections(SourceInstanceId source, int limit);

  /** 원본 카탈로그 한 쪽(observed_at 역순 → 소스 → externalId). */
  RawCatalogPage rawList(RawCatalogQuery query);

  Optional<RawEntry> rawGet(SourceInstanceId source, String externalId);

  /** 모든 소스의 최근 run(started_at 역순). */
  List<CollectionRun> recentRuns(int limit);

  /** 모든 점유 행(만료된 것 포함, 소스 순). */
  List<WorkClaim> claims();

  /**
   * TS SourceStatus. running = running 상태 run이 있다, recentFailureCount = 마지막
   * 성공(succeeded·succeeded_with_rejections) 이후의 failed·partially_succeeded 수, lastSuccessAt = 그 성공의
   * finished_at. 마지막 세 값은 TS에 없는 스냅샷 축소 보호 상태(ADR-0028): 아직 풀리지 않은 보호 판정, 대기 중인 허용 표시의 시각, 소스별 기준.
   */
  record SourceStatus(
      SourceInstanceId source,
      boolean enabled,
      boolean running,
      int currentItemCount,
      int tombstoneCount,
      int recentFailureCount,
      Optional<Instant> activeLeaseExpiresAt,
      Optional<RunStore.SnapshotCompletion> lastCompleteSnapshot,
      Optional<LastRun> lastRun,
      Optional<Instant> lastSuccessAt,
      Optional<SnapshotGuardStore.Guarded> reconcileGuard,
      Optional<Instant> shrinkAcceptedAt,
      SnapshotShrinkOverrides shrinkGuard) {}

  /** 가장 최근 run(started_at, run_id 역순 첫 행). */
  record LastRun(
      Instant startedAt,
      RunStatus status,
      Optional<String> errorKind,
      Optional<String> errorSummary) {}

  record RecentRejection(RunId runId, Instant capturedAt, RejectedItem rejection) {}

  enum RawState {
    ACTIVE,
    DELETED;

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static RawState fromWire(String s) {
      for (RawState state : values()) if (state.wire().equals(s)) return state;
      throw new IllegalArgumentException(
          "argument '" + s + "' is invalid. Allowed choices are active, deleted.");
    }
  }

  /** TS RawItemQuery(CLI가 쓰는 필터만). */
  record RawCatalogQuery(
      int limit,
      Optional<RawCatalogCursor> cursor,
      Optional<SourceInstanceId> source,
      Optional<String> resourceType,
      Optional<RawState> state) {
    public RawCatalogQuery {
      if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    }
  }

  /** 원본 레코드와 그 소스(TS RawSourceRecord의 key.sourceInstanceId). */
  record RawEntry(SourceInstanceId source, RawRecord record) {}

  record RawCatalogPage(List<RawEntry> items, Optional<RawCatalogCursor> nextCursor) {}
}
