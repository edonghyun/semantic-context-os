package io.scos.client;

import io.scos.client.collect.CollectLock;
import io.scos.client.collect.CollectionReport;
import io.scos.client.collect.CollectionTask;
import io.scos.client.collect.IncrementalCollector;
import io.scos.client.collect.StateStore;
import io.scos.client.source.memory.MemoryAdapter;
import io.scos.client.source.memory.MemoryProjector;
import io.scos.client.source.transcript.TranscriptAdapter;
import io.scos.client.source.transcript.TranscriptProjector;
import io.scos.engine.ContextIngestor;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 잠금·state·수집기와 취소 신호의 수명을 함께 관리한다. 단일 수집 스레드에서 실행한다. */
final class CollectionSession implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(CollectionSession.class);

  /** 색인이 없을 때는 원문만 보존하고 투영 대기는 비우지 않는다. */
  private static final ContextIngestor UNAVAILABLE_INDEX =
      (space, sourceId, documents, live) -> {
        throw new IllegalStateException("색인을 열 수 없어 투영하지 않는다");
      };

  private final ClientPaths paths;
  private final List<IncrementalCollector.Source> sources;
  private final List<String> kinds;

  /** 색인은 조립자가 소유하며 세션은 투영할 때만 빌린다. */
  private final @Nullable LocalEngine engine;

  private final UnaryOperator<CollectionTask> collectors;
  private @Nullable CollectLock lock;
  private @Nullable StateStore state;
  private volatile @Nullable CollectionTask collector;
  private volatile boolean cancelled;

  private CollectionSession(
      ClientPaths paths, ClientOptions options, @Nullable LocalEngine engine) {
    this.paths = paths;
    this.sources = sources(paths, options.clock());
    this.kinds = sources.stream().map(source -> source.adapter().kind()).toList();
    this.engine = engine;
    this.collectors = options.collectors();
  }

  static CollectionSession indexed(ClientPaths paths, ClientOptions options, LocalEngine engine) {
    return new CollectionSession(paths, options, engine);
  }

  /** 색인을 건드리지 않고 원문과 투영 대기를 보존하는 저하 수집이다. */
  static CollectionSession stateOnly(ClientPaths paths, ClientOptions options) {
    PrivateFiles.directory(paths.home());
    return new CollectionSession(paths, options, null);
  }

  /** 잠금을 닫을 때까지 보유한다. 잠금을 못 잡으면 empty, 원천 루트가 없으면 빈 보고다. */
  synchronized Optional<CollectionReport> collectOnce() {
    CollectionTask current = collector;
    if (current == null) {
      Optional<CollectLock> acquired = CollectLock.tryAcquire(paths.lock());
      if (acquired.isEmpty()) return Optional.empty();
      if (engine == null && !Files.isDirectory(paths.claudeProjects())) {
        acquired.get().close();
        return Optional.of(CollectionReport.empty());
      }
      current = acquire(acquired.get());
    }
    if (cancelled) return Optional.of(CollectionReport.empty());
    if (!Files.isDirectory(paths.claudeProjects())) {
      log.info("원천 루트 {}가 없어 이번 주기 수집을 건너뛴다", paths.claudeProjects());
      return Optional.of(CollectionReport.empty());
    }
    return Optional.of(current.runOnce());
  }

  private CollectionTask acquire(CollectLock acquired) {
    StateStore opened = null;
    try {
      PrivateFiles.database(paths.state());
      opened = StateStore.open(paths.state());
      CollectionTask created =
          collectors.apply(
              new IncrementalCollector(
                  opened,
                  engine == null ? UNAVAILABLE_INDEX : engine.ingestor(),
                  ClientPaths.SPACE,
                  sources));
      collector = created;
      // cancel이 수집기 생성 전에 왔어도 새 수집기에 전달한다.
      if (cancelled) created.cancel();
      // 파일 생성 여부가 아니라 내용으로 본다: 다른 프로세스가 만든 빈 색인도 복구한다.
      if (!cancelled && needsReprojection() && hasDocuments(opened)) {
        log.info("색인이 비어 state에서 다시 투영한다: {}", created.fullReproject());
      }
      lock = acquired;
      state = opened;
      return created;
    } catch (RuntimeException | Error failure) {
      collector = null;
      try {
        if (opened != null) opened.close();
      } finally {
        acquired.close();
      }
      throw failure;
    }
  }

  static List<IncrementalCollector.Source> sources(ClientPaths paths, Clock clock) {
    return List.of(
        new IncrementalCollector.Source(
            new TranscriptAdapter(paths.claudeProjects(), clock),
            new TranscriptProjector(),
            Set.of(TranscriptAdapter.TURN)),
        new IncrementalCollector.Source(
            new MemoryAdapter(paths.claudeProjects(), clock),
            new MemoryProjector(),
            Set.of(MemoryAdapter.RESOURCE_TYPE)));
  }

  private boolean needsReprojection() {
    return engine != null && engine.isEmpty(kinds);
  }

  private boolean hasDocuments(StateStore opened) {
    return kinds.stream().anyMatch(kind -> !opened.documentIds(kind).isEmpty());
  }

  /** 실행 중인 주기를 다음 취소 확인 지점에서 끝낸다. 다른 스레드에서 호출할 수 있다. */
  void cancel() {
    cancelled = true;
    CollectionTask current = collector;
    if (current != null) current.cancel();
  }

  /** 진행 중인 주기가 끝난 뒤 state와 잠금을 닫는다. */
  @Override
  public synchronized void close() {
    StateStore opened = state;
    CollectLock held = lock;
    state = null;
    lock = null;
    collector = null;
    try {
      if (opened != null) opened.close();
    } finally {
      if (held != null) held.close();
    }
  }
}
