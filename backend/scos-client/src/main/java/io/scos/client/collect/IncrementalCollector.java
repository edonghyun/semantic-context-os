package io.scos.client.collect;

import io.scos.engine.ContextIngestor;
import io.scos.engine.EventLog;
import io.scos.engine.IngestFailure;
import io.scos.engine.IngestReport;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 원천마다 증분 수집(어댑터 → state, 페이지 하나가 한 트랜잭션)한 뒤 닿은 문서만 투영해 색인에 넣는다. 어댑터가 낸 Upsert·Delete만 적용하고, 이번
 * run에 안 보인 항목을 쓸어 지우지 않는다(Claude Code가 지운 트랜스크립트는 남는다).
 */
public final class IncrementalCollector implements CollectionTask {
  /** 원천 하나: 어댑터, 투영기, 투영이 읽는 자원 종류(원문 보존용 줄 같은 나머지는 읽지 않는다). */
  public static final class Source {
    private final SourceAdapter adapter;
    private final Projector projector;
    private final Set<String> projectedTypes;
    private final SourceDescriptor descriptor;

    public Source(SourceAdapter adapter, Projector projector, Set<String> projectedTypes) {
      this.adapter = adapter;
      this.projector = projector;
      this.projectedTypes = Set.copyOf(projectedTypes);
      String kind = adapter.kind();
      descriptor =
          new SourceDescriptor(
              new SourceInstanceId(kind), kind, kind, new SourceConfig(1, "{}"), "");
    }

    public SourceAdapter adapter() {
      return adapter;
    }

    public Projector projector() {
      return projector;
    }

    public Set<String> projectedTypes() {
      return projectedTypes;
    }

    public SourceDescriptor descriptor() {
      return descriptor;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(IncrementalCollector.class);

  /** 투영 한 번(인제스트 한 트랜잭션)에 넣는 문서 수 — 묶음 사이에서 취소를 본다. */
  static final int PROJECTION_BATCH = 50;

  private final StateStore state;
  private final ContextIngestor ingestor;
  private final SpaceId space;
  private final List<Source> sources;
  private final ContentCapture capture = new LocalContentCapture();
  private final CancellationToken cancel = CancellationToken.none();
  private final CallExecutor calls = LocalCalls.INSTANCE;

  public IncrementalCollector(
      StateStore state, ContextIngestor ingestor, SpaceId space, List<Source> sources) {
    this.state = state;
    this.ingestor = ingestor;
    this.space = space;
    this.sources = List.copyOf(sources);
  }

  /** 진행 중인 수집을 멈추게 한다(종료 때). 어댑터가 다음 확인 지점에서 끝낸다. */
  @Override
  public void cancel() {
    cancel.cancel();
  }

  /** 한 번 수집하고 닿은 문서만 투영한다. 원천 하나가 실패해도 로그를 남기고 다음 원천을 잇는다(보고의 failedSources). */
  @Override
  public CollectionReport runOnce() {
    Totals totals = new Totals();
    for (Source source : sources) {
      try {
        collect(source, totals);
      } catch (RuntimeException e) {
        totals.failedSources++;
        EventLog.warn(log, "client.source.failed")
            .source(new SourceInstanceId(source.adapter().kind()))
            .detail("operation", "client.source.collect", "errorType", e.getClass().getSimpleName())
            .log();
      }
    }
    return totals.report();
  }

  /**
   * 수집 없이 state의 모든 문서를 투영한다(`index.sqlite`를 새로 만든 뒤). 먼저 전부 투영 대기 목록에 넣고 묶음으로 비우므로, 취소·종료로 끊기면 다음
   * run 시작의 대기 목록 처리가 나머지를 잇는다.
   */
  @Override
  public CollectionReport fullReproject() {
    Totals totals = new Totals();
    if (cancel.isCancelled()) return totals.report();
    state.markAllPending(sources.stream().map(s -> s.adapter().kind()).toList());
    for (Source source : sources) {
      drain(source, state.pendingProjection(source.adapter().kind()), totals);
    }
    return totals.report();
  }

  private void collect(Source source, Totals totals) {
    String kind = source.adapter().kind();
    // 지난 run이 중단돼 못 한 투영을 먼저 잇는다
    drain(source, state.pendingProjection(kind), totals);
    if (cancel.isCancelled()) return;
    CollectionInput input =
        new CollectionInput(
            source.descriptor(),
            state.checkpoint(kind),
            CollectionMode.INCREMENTAL,
            calls,
            capture,
            cancel);
    RuntimeException failure = null;
    try {
      source
          .adapter()
          .collect(
              input,
              page -> {
                state.commit(kind, page);
                totals.pages++;
                totals.rejected += page.rejections().size();
                for (RawMutation m : page.mutations()) {
                  if (m instanceof RawMutation.Upsert) totals.upserts++;
                  else totals.deletes++;
                }
              });
    } catch (RuntimeException e) {
      failure = e;
    }
    // 어댑터가 중간에 실패해도 이미 커밋한 페이지의 문서는 색인에 반영한다(투영 실패는 drain이 삼켜 어댑터의 원래 실패를 가리지 않는다)
    drain(source, state.pendingProjection(kind), totals);
    if (failure != null) throw failure;
  }

  /**
   * 문서를 묶음으로 투영한다. 취소되면 다음 묶음 전에 멈추고 남은 문서는 대기 목록에 남는다. 색인은 다시 만들 수 있는 캐시라 투영 실패는 state 수집을 막지 않는다:
   * 실패하면 로그 한 줄과 보고(documentsFailed)에 남기고 이 호출의 나머지 묶음은 건너뛴다(대기 목록에 남아 다음 run이 다시 시도한다).
   */
  private void drain(Source source, Set<String> documentIds, Totals totals) {
    if (documentIds.isEmpty() || cancel.isCancelled()) return;
    Set<String> live = state.documentIds(source.adapter().kind());
    List<String> ids = new ArrayList<>(documentIds);
    for (int from = 0; from < ids.size(); from += PROJECTION_BATCH) {
      if (cancel.isCancelled()) return;
      int to = Math.min(ids.size(), from + PROJECTION_BATCH);
      try {
        project(source, new TreeSet<>(ids.subList(from, to)), live, totals);
      } catch (StateStore.Failure e) {
        throw e;
      } catch (RuntimeException e) {
        EventLog.warn(log, "client.projection.failed")
            .source(new SourceInstanceId(source.adapter().kind()))
            .detail(
                "operation",
                "client.projection.drain",
                "pendingDocuments",
                ids.size() - from,
                "errorType",
                e.getClass().getSimpleName())
            .log();
        totals.documentsFailed += ids.size() - from;
        return;
      }
    }
  }

  private void project(
      Source source, Set<String> documentIds, Set<String> liveDocumentIds, Totals totals) {
    if (documentIds.isEmpty()) return;
    String kind = source.adapter().kind();
    Map<String, List<RawItem>> groups =
        source
            .projector()
            .groupIntoDocuments(state.items(kind, documentIds, source.projectedTypes()));
    List<SourceDocument> documents = new ArrayList<>();
    groups.forEach(
        (documentId, items) -> {
          if (!documentIds.contains(documentId)) return;
          Optional<SourceDocument> doc =
              source.projector().project(kind, documentId, items, IncrementalCollector::read);
          doc.ifPresent(documents::add);
        });
    // 닿았지만 이제 투영할 본문이 없는 문서(비운 메모리 파일 등)는 살아 있는 문서에서 빼 유닛을 지운다
    Set<String> live = new TreeSet<>(liveDocumentIds);
    live.removeAll(documentIds);
    documents.forEach(d -> live.add(d.documentId()));
    IngestReport report = ingestor.ingest(space, kind, documents, live);
    Set<String> failed = failedDocumentIds(kind, documents, report.failures());
    Set<String> done = new TreeSet<>(documentIds);
    done.removeAll(failed);
    // 격리된 문서는 대기 목록에 남겨 다음 주기에 다시 투영한다
    state.clearPending(kind, done);
    totals.documentsProjected += documents.size() - failed.size();
    totals.documentsFailed += failed.size();
    totals.unitsDeleted += report.deleted();
  }

  /** 인제스트가 격리한 문서 id. 실패는 uri로 오므로 이번 묶음 문서의 uri로 되짚는다. 실패마다 로그 한 줄(원문 없음). */
  private static Set<String> failedDocumentIds(
      String kind, List<SourceDocument> documents, List<IngestFailure> failures) {
    Set<String> failed = new TreeSet<>();
    for (IngestFailure f : failures) {
      for (SourceDocument d : documents) {
        if (!d.uri().equals(f.uri())) continue;
        failed.add(d.documentId());
        EventLog.warn(log, "client.document.failed")
            .source(new SourceInstanceId(kind))
            .detail(
                "operation",
                "client.document.ingest",
                "documentId",
                d.documentId(),
                "stage",
                f.stage())
            .log();
      }
    }
    return failed;
  }

  private static Optional<String> read(RawItem item) {
    return item.content()
        .filter(RawContent.Inline.class::isInstance)
        .map(c -> ((RawContent.Inline) c).valueJson());
  }

  /** 로컬 파일 원천에는 외부 호출이 없다. 잘못된 사용은 호출을 실행하기 전에 거절한다. */
  private enum LocalCalls implements CallExecutor {
    INSTANCE;

    @Override
    public <T> T execute(
        String operation,
        Call<T> call,
        Function<Exception, CallFailure> classify,
        CancellationToken token) {
      throw new UnsupportedOperationException("local sources do not support external calls");
    }

    @Override
    public Duration requestTimeout() {
      return Duration.ofSeconds(30);
    }
  }

  private static final class Totals {
    int pages;
    int upserts;
    int deletes;
    int rejected;
    int documentsProjected;
    int documentsFailed;
    int unitsDeleted;
    int failedSources;

    CollectionReport report() {
      return new CollectionReport(
          pages,
          upserts,
          deletes,
          rejected,
          documentsProjected,
          documentsFailed,
          unitsDeleted,
          failedSources);
    }
  }
}
