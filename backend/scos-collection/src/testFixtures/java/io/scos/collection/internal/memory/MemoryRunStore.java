package io.scos.collection.internal.memory;

import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStateException;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.RunStore;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 메모리 참조 구현. */
public class MemoryRunStore implements RunStore {
  private final Map<RunId, CollectionRun> runs = new LinkedHashMap<>();
  private final Map<RunId, Set<String>> observations = new HashMap<>();
  private final Map<RunId, List<RejectedItem>> rejections = new HashMap<>();
  private final Map<SourceInstanceId, SnapshotCompletion> snapshots = new HashMap<>();
  private final Map<String, byte[]> blobs = new HashMap<>();

  /**
   * 가짜 수집기용(제품 경로가 부른다): 실제 런타임처럼 run을 시작하고(같은 소스의 running run은 stale_run으로 마감) 바로 succeeded로 끝낸다.
   * 제품이 부르는 쪽이라 전제 검사를 하지 않는다 — "given 준비 실패"를 던지면 감독이 그것을 실패 시도로 삼켜 테스트가 엉뚱하게 녹색이 된다. 테스트 given은
   * {@link #givenFinished}를 쓴다.
   */
  public synchronized CollectionRun recordSucceeded(RunId id, SourceInstanceId source, Instant at) {
    start(id, source, at);
    return finish(
        id,
        RunStatus.SUCCEEDED,
        CollectionCoverage.INCREMENTAL,
        at,
        0,
        Optional.empty(),
        Optional.empty(),
        0);
  }

  /**
   * 테스트 준비(given 전용 — 가짜 협력자 안에서 쓰지 않는다): 시작·완료가 기록된 run 하나(성공이면 오류 0, 아니면 1). 감독이 다음 due를 계산하는 근거가
   * 된다. 내부에서 {@link #start}를 부르므로 같은 소스에 running run이 있으면 그것을 stale_run으로 마감하게 된다 — 전제 밖의 상태를 바꾸지
   * 않도록 그때는 given 준비 실패로 멈춘다.
   */
  public synchronized CollectionRun givenFinished(
      RunId id, SourceInstanceId source, RunStatus status, Instant startedAt, Instant finishedAt) {
    for (CollectionRun r : runs.values())
      if (r.source().equals(source) && r.status() == RunStatus.RUNNING)
        throw new IllegalStateException(
            "given 준비 실패: 소스 "
                + source.value()
                + "에 running run "
                + r.id().value()
                + "이 있다 — 끝난 run을 더하면 그 run이 stale_run으로 마감된다");
    if (runs.containsKey(id))
      throw new IllegalStateException("given 준비 실패: run " + id.value() + "이 이미 있다");
    start(id, source, startedAt);
    return finish(
        id,
        status,
        CollectionCoverage.INCREMENTAL,
        finishedAt,
        status == RunStatus.SUCCEEDED ? 0 : 1,
        Optional.empty(),
        Optional.empty(),
        0);
  }

  @Override
  public synchronized CollectionRun start(RunId id, SourceInstanceId source, Instant startedAt) {
    for (var e : runs.entrySet()) {
      CollectionRun r = e.getValue();
      if (r.source().equals(source) && r.status() == RunStatus.RUNNING) {
        e.setValue(
            new CollectionRun(
                r.id(),
                r.source(),
                RunStatus.FAILED,
                r.startedAt(),
                Optional.of(startedAt),
                r.pagesApplied(),
                r.upserted(),
                r.deleted(),
                r.unchanged(),
                r.rejected(),
                r.errorCount() + 1,
                Optional.of("stale_run"),
                Optional.of("superseded by a new run"),
                r.coverage()));
        observations.remove(r.id());
      }
    }
    CollectionRun run =
        new CollectionRun(
            id,
            source,
            RunStatus.RUNNING,
            startedAt,
            Optional.empty(),
            0,
            0,
            0,
            0,
            0,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    runs.put(id, run);
    return run;
  }

  @Override
  public synchronized Optional<CollectionRun> get(RunId id) {
    return Optional.ofNullable(runs.get(id));
  }

  @Override
  public synchronized CollectionRun requireRunning(RunId id, SourceInstanceId source) {
    CollectionRun run = runs.get(id);
    if (run == null) throw new RunStateException("Sync run not found: " + id.value());
    if (run.status() != RunStatus.RUNNING)
      throw new RunStateException("Sync run is not running: " + id.value());
    if (!run.source().equals(source))
      throw new RunStateException(
          "Sync run "
              + id.value()
              + " belongs to "
              + run.source().value()
              + ", not "
              + source.value());
    return run;
  }

  @Override
  public synchronized void addPage(
      RunId id, int upserted, int deleted, int unchanged, int rejected) {
    CollectionRun r = runs.get(id);
    runs.put(
        id,
        new CollectionRun(
            r.id(),
            r.source(),
            r.status(),
            r.startedAt(),
            r.finishedAt(),
            r.pagesApplied() + 1,
            r.upserted() + upserted,
            r.deleted() + deleted,
            r.unchanged() + unchanged,
            r.rejected() + rejected,
            r.errorCount(),
            r.errorKind(),
            r.errorSummary(),
            r.coverage()));
  }

  @Override
  public synchronized void observe(RunId id, Collection<String> externalIds) {
    observations.computeIfAbsent(id, k -> new HashSet<>()).addAll(externalIds);
  }

  @Override
  public synchronized Set<String> observations(RunId id) {
    return new HashSet<>(observations.getOrDefault(id, Set.of()));
  }

  @Override
  public synchronized CollectionRun finish(
      RunId id,
      RunStatus status,
      CollectionCoverage coverage,
      Instant finishedAt,
      int errorCount,
      Optional<String> errorKind,
      Optional<String> errorSummary,
      int reconciled) {
    CollectionRun r = runs.get(id);
    CollectionRun finished =
        new CollectionRun(
            r.id(),
            r.source(),
            status,
            r.startedAt(),
            Optional.of(finishedAt),
            r.pagesApplied(),
            r.upserted(),
            r.deleted() + reconciled,
            r.unchanged(),
            r.rejected(),
            errorCount,
            errorKind,
            errorSummary,
            Optional.of(coverage));
    runs.put(id, finished);
    observations.remove(id);
    return finished;
  }

  @Override
  public synchronized void addRejections(
      RunId id, SourceInstanceId source, Instant capturedAt, List<RejectedItem> items) {
    List<RejectedItem> stored = new ArrayList<>();
    for (RejectedItem r : items) {
      if (r.quarantined().orElse(null) instanceof RawContent.Blob b && b.data().isPresent()) {
        blobs.putIfAbsent(b.sha256(), b.data().get());
        stored.add(
            new RejectedItem(
                r.failureKind(),
                r.message(),
                r.providerLocatorJson(),
                r.observedAt(),
                Optional.of(b.stored())));
      } else stored.add(r);
    }
    rejections.computeIfAbsent(id, k -> new ArrayList<>()).addAll(stored);
  }

  public synchronized Optional<byte[]> blob(String sha256) {
    return Optional.ofNullable(blobs.get(sha256)).map(byte[]::clone);
  }

  @Override
  public synchronized List<RejectedItem> rejections(RunId id) {
    return List.copyOf(rejections.getOrDefault(id, List.of()));
  }

  @Override
  public synchronized Optional<CollectionRun> latest(SourceInstanceId source) {
    return runs.values().stream()
        .filter(r -> r.source().equals(source))
        .max(Comparator.comparing(CollectionRun::startedAt).thenComparing(r -> r.id().value()));
  }

  @Override
  public synchronized int recentFailureCount(SourceInstanceId source) {
    List<CollectionRun> recent =
        runs.values().stream()
            .filter(r -> r.source().equals(source))
            .sorted(
                Comparator.comparing(CollectionRun::startedAt)
                    .thenComparing((CollectionRun r) -> r.id().value())
                    .reversed())
            .toList();
    int count = 0;
    for (CollectionRun r : recent) {
      if (r.status() == RunStatus.SUCCEEDED || r.status() == RunStatus.SUCCEEDED_WITH_REJECTIONS)
        break;
      if (r.status() == RunStatus.FAILED || r.status() == RunStatus.PARTIALLY_SUCCEEDED) count++;
    }
    return count;
  }

  @Override
  public synchronized void recordSnapshotCompletion(
      SourceInstanceId source, Instant completedAt, int deletionCount) {
    snapshots.put(source, new SnapshotCompletion(completedAt, deletionCount));
  }

  @Override
  public synchronized Optional<SnapshotCompletion> lastSnapshotCompletion(SourceInstanceId source) {
    return Optional.ofNullable(snapshots.get(source));
  }
}
