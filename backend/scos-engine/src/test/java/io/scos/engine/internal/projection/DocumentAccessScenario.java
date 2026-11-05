package io.scos.engine.internal.projection;

import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.engine.support.projection.SyncReport;
import io.scos.engine.support.projection.WriteGuardException;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryDocumentOwnership;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemorySpaceMeta;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/** 실제 메모리 저장 포트 위에서 ACL 선행 결정과 유닛 저장의 결과를 관찰한다. */
final class DocumentAccessScenario implements AutoCloseable {
  static final SpaceId SPACE = new SpaceId("legacy");
  static final Instant T0 = Instant.parse("2026-11-03T00:00:00Z");
  static final AccessKey OWNER = AccessKey.parse("user:source-a:owner");
  static final AccessKey FOREIGN = AccessKey.parse("user:source-b:reader");
  final MemoryAclStore acls = new MemoryAclStore();
  final MemoryDocumentOwnership ownership = new MemoryDocumentOwnership();
  final MemorySpaceMeta meta = new MemorySpaceMeta();
  final MutableClock clock = new MutableClock(T0);
  private final MemoryUnitStore units = new MemoryUnitStore(acls, ownership);
  private final MemoryVectorIndex vectors = new MemoryVectorIndex();
  private final MemoryUnitMaintenance maintenance = new MemoryUnitMaintenance(units, vectors);
  private final RecordingEmbedder embedder = new RecordingEmbedder();
  private final Transactions transactions = new Transactions();
  private final DocumentIngestor ingestor = ingestor(maintenance);

  private DocumentIngestor ingestor(UnitMaintenance unitMaintenance) {
    return new DocumentIngestor(
        transactions,
        unitMaintenance,
        new MemoryKeywordIndex(),
        vectors,
        meta,
        embedder,
        EmbeddingCache.NONE,
        new IngestPipeline(new JtokkitTokenizer()),
        clock,
        acls,
        ownership);
  }

  private final DocumentIngestor keywordIngestor =
      new DocumentIngestor(
          TransactionOperations.withoutTransaction(),
          maintenance,
          new MemoryKeywordIndex(),
          vectors,
          meta,
          new KeywordOnlyUnitEmbedding(),
          new IngestPipeline(new JtokkitTokenizer()),
          clock);
  private List<String> previousUnitIds = List.of();

  void decideFailure(AccessFailureReason reason) {
    decide(Map.of("doc-01", new DocumentAcl("doc-01", Status.FAILED, Set.of(), T0, reason)));
  }

  void decideAllowed() {
    decide(Map.of("doc-01", new DocumentAcl("doc-01", Status.OK, Set.of(OWNER), T0)));
  }

  void decideAllowedBatch(int count) {
    Map<String, DocumentAcl> decisions =
        IntStream.range(0, count)
            .boxed()
            .collect(
                Collectors.toMap(
                    i -> "doc-" + i,
                    i -> new DocumentAcl("doc-" + i, Status.OK, Set.of(OWNER), T0)));
    decide(decisions);
  }

  private void decide(Map<String, DocumentAcl> decisions) {
    ingestor.sync(SPACE, "source-a", List.of(), decisions.keySet(), decisions, () -> {});
  }

  int transactionRequests() {
    return transactions.requests;
  }

  void beginCommitNotifications() {
    TransactionSynchronizationManager.initSynchronization();
  }

  void commitNotifications() {
    TransactionSynchronizationUtils.triggerAfterCommit();
  }

  @Override
  public void close() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
  }

  private static final class Transactions implements TransactionOperations {
    private int requests;

    @Override
    public <T> T execute(TransactionCallback<T> action) {
      requests++;
      return TransactionOperations.withoutTransaction().execute(action);
    }
  }

  void givenProjectedUnits() {
    var report = syncTenDocuments();
    if (!report.errors().isEmpty()) throw new IllegalStateException("given 준비 실패: 초기 유닛 투영 실패");
    previousUnitIds = maintenance.listAll(SPACE).stream().map(ContextUnit::id).toList();
  }

  SyncReport clearDocuments() {
    return clearDocuments(() -> {});
  }

  private SyncReport clearDocuments(Runnable guard) {
    return ingestor.sync(SPACE, "source-a", List.of(), Set.of(), Map.of(), guard);
  }

  void pruneWithRejectedGuard() {
    clearDocuments(
        () -> {
          throw new WriteGuardException("injected lost projection claim");
        });
  }

  void pruneAcrossGenerationChange() {
    clearDocuments(this::pendingGeneration);
  }

  SyncReport repeatWithUnavailableUnitWrites() {
    var unavailable =
        (UnitMaintenance)
            Proxy.newProxyInstance(
                UnitMaintenance.class.getClassLoader(),
                new Class<?>[] {UnitMaintenance.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("upsert"))
                    throw new IllegalStateException("unit writes unavailable");
                  try {
                    return method.invoke(maintenance, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    return syncTenDocuments(ingestor(unavailable));
  }

  void pruneForeignIndexCandidate() {
    var stale =
        (UnitMaintenance)
            Proxy.newProxyInstance(
                UnitMaintenance.class.getClassLoader(),
                new Class<?>[] {UnitMaintenance.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("listDocumentIds")) return List.of("doc-01");
                  try {
                    return method.invoke(maintenance, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    ingestor(stale).sync(SPACE, "source-b", List.of(), Set.of(), Map.of(), () -> {});
  }

  Map<String, double[]> storedVectorsForPreviousUnits() {
    return maintenance.storedVectors(SPACE, previousUnitIds);
  }

  void givenKeywordDocument() {
    var report = keywordSync(IngestSamples.BODY_B);
    if (!report.errors().isEmpty()) throw new IllegalStateException("given 준비 실패: 키워드 문서 저장 실패");
    previousUnitIds = maintenance.listAll(SPACE).stream().map(ContextUnit::id).toList();
  }

  void givenKeywordDocumentWithStaleVector() {
    givenKeywordDocument();
    var unit = maintenance.listAll(SPACE).getFirst();
    vectors.upsert(
        SPACE,
        List.of(
            new IndexedUnit(
                unit.id(), unit.documentId(), unit.partition(), false, new double[] {1})));
  }

  SyncReport repeatKeywordDocument() {
    return keywordSync(IngestSamples.BODY_B);
  }

  SyncReport rewriteKeywordDocument() {
    return keywordSync(IngestSamples.BODY_B.replace("두 단계", "세 단계"));
  }

  private SyncReport keywordSync(String body) {
    return keywordIngestor.sync(
        SPACE, "source-a", List.of(document("doc-01", body)), Set.of("doc-01"), Map.of(), () -> {});
  }

  void foreignSpaceOwnsOneDocument() {
    ownership.claim(new SpaceId("other"), "source-a", "doc-05");
  }

  SyncReport syncTenDocuments() {
    return syncTenDocuments(ingestor);
  }

  private SyncReport syncTenDocuments(DocumentIngestor target) {
    var documents =
        IntStream.rangeClosed(1, 10).mapToObj(i -> document("doc-%02d".formatted(i))).toList();
    Set<String> live =
        documents.stream().map(SourceDocument::documentId).collect(Collectors.toSet());
    Map<String, DocumentAcl> candidates =
        live.stream()
            .collect(
                Collectors.toMap(
                    id -> id, id -> new DocumentAcl(id, Status.OK, Set.of(OWNER), T0)));
    return target.sync(SPACE, "source-a", documents, live, candidates, () -> {});
  }

  Set<String> aclIds() {
    return acls.get(
            SPACE,
            List.of(
                "doc-01", "doc-02", "doc-03", "doc-04", "doc-05", "doc-06", "doc-07", "doc-08",
                "doc-09", "doc-10"))
        .keySet();
  }

  Set<String> storedDocuments() {
    return maintenance.listAll(SPACE).stream()
        .map(ContextUnit::documentId)
        .collect(Collectors.toSet());
  }

  void givenOwnedDocument() {
    syncAcl("source-a", new DocumentAcl("doc-01", Status.OK, Set.of(OWNER), T0));
  }

  SyncReport restrictAtSameClock() {
    return syncAcl("source-a", new DocumentAcl("doc-01", Status.FAILED, Set.of(), T0));
  }

  SyncReport syncForeignDocument() {
    return syncAcl(
        "source-b", new DocumentAcl("doc-01", Status.OK, Set.of(FOREIGN), T0.plusSeconds(1)));
  }

  void pendingGeneration() {
    meta.set(SPACE, "embedding:version", "pending:test:recording:4:title+aliases+content");
  }

  DocumentAcl acl() {
    return acls.get(SPACE, List.of("doc-01")).get("doc-01");
  }

  private SyncReport syncAcl(String source, DocumentAcl candidate) {
    return ingestor.sync(
        SPACE, source, List.of(), Set.of("doc-01"), Map.of("doc-01", candidate), () -> {});
  }

  private SourceDocument document(String id) {
    return document(id, IngestSamples.BODY_B);
  }

  private SourceDocument document(String id, String body) {
    return new SourceDocument(
        "source-a",
        id,
        "slack://source-a/" + id,
        "제목 " + id,
        body,
        "2026-11-03T00:00:00Z",
        "2026-11-03T00:00:00Z");
  }
}
