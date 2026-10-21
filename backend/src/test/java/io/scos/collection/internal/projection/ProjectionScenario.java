package io.scos.collection.internal.projection;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.engine.internal.search.QuestionSearch;
import io.scos.engine.internal.search.Reranker;
import io.scos.engine.internal.search.SearchSettings;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.HoldGate;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestExecutors;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집 → 투영 DB 종단 테스트의 연결을 소유한다: 가짜 Slack·PostgreSQL 어댑터·실제 수집 런타임·투영·결정론 임베더·가짜 시계·heartbeat 스케줄러.
 * 본문은 "무엇을 수집·변경하고 임베더가 어떻게 행동할 때 투영하면 무엇이 남는가"만 쓴다. 만들 때 DB를 비운다(TESTING §6.1).
 */
@Tag("db")
final class ProjectionScenario implements AutoCloseable {
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"));
  static final SourceInstanceId SOURCE = SlackAssembly.SOURCE;
  static final SpaceId SPACE = PostgresProjection.SPACE;
  static final Duration FIRST_BACKOFF = Duration.ofSeconds(10);
  static final Duration LEASE_TTL = Duration.ofSeconds(30);
  static final ProjectionRetryPolicy RETRY =
      new ProjectionRetryPolicy(FIRST_BACKOFF, Duration.ofMinutes(5), 2);
  static final String MAIN_CHANNEL = "C0000000001";
  static final String THREAD_DOCUMENT = MAIN_CHANNEL + "/thread-1760400100.000000";

  /** 늦은 쓰기가 덮어썼는지 구분하는 표식 벡터 값. */
  static final double SENTINEL = 9.0;

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(60);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
  private final ScriptedEmbedder embedder = new ScriptedEmbedder();
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final List<ProjectionRun.Report> competitorReports = new CopyOnWriteArrayList<>();
  private final DataSource ds;
  private final PostgresCollectionConfiguration.Adapters adapters;
  private final FakeSlackServer slack;
  private final CollectionRuntime runtime;
  private final UnitStore units;
  private final PostgresProjection projections;
  private final ProjectionRun projection;

  ProjectionScenario() {
    PostgresSupport.truncateAll();
    ds = PostgresSupport.dataSource();
    adapters = PostgresCollectionConfiguration.adapters(ds);
    try {
      slack = new FakeSlackServer(FIXTURES.resolve("slack-dataset.json"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    adapters
        .sources()
        .save(SlackAssembly.instance(SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP, clock.instant()));
    runtime =
        PostgresCollectionRuntime.over(ds, clock)
            .adapter(SlackAssembly.adapter(slack.baseUrl(), slack.token(), clock))
            .owner("w1")
            .leaseTtl(LEASE_TTL)
            .heartbeats(scheduler)
            .build();
    units = PostgresTestAdapters.unitStore(ds);
    projections =
        PostgresProjection.over(ds, clock, embedder)
            .retry(RETRY)
            .leaseTtl(LEASE_TTL)
            .metrics(ProjectionMetrics.of(metrics));
    projection = projections.run("p1", scheduler);
  }

  static List<String> expectedUnitIds() {
    return expectedUnits().stream().map(u -> u.get("id").asString()).toList();
  }

  static List<String> expectedUnitContents() {
    return expectedUnits().stream().map(u -> u.get("content").asString()).toList();
  }

  // ---- 수집·변경 ----

  void collect() {
    runtime.collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  CollectionRuntime.Report collectSnapshot() {
    return runtime.collect(SOURCE, CollectionMode.SNAPSHOT, CancellationToken.none());
  }

  void slackMessageEdited(String ts, String newText, String editedTs) {
    slack.editMessage(MAIN_CHANNEL, ts, newText, editedTs);
  }

  void slackMessageDeleted(String channel, String ts) {
    slack.deleteMessage(channel, ts);
  }

  /** 수집이 거부한 항목(C0000000002의 ts 없는 메시지)이 공급자에서 사라진다. */
  void rejectedSlackItemDisappears() {
    slack.deleteMessage("C0000000002", "");
  }

  void slackMessageAdded(String text, String ts) {
    slack.addMessage("C0000000002", 0, "U09", text, ts);
  }

  void requestProjection() {
    adapters.outbox().requestProjection(SOURCE, clock.instant());
  }

  /** 공간의 임베딩 세대를 옛 값으로 되돌린다 — 다음 투영이 백필한다. */
  String givenStaleEmbeddingGeneration() {
    String current =
        embeddingGeneration()
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: 현재 임베딩 세대가 기록돼 있어야 한다"));
    PostgresTestAdapters.spaceMeta(ds)
        .set(SPACE, "embedding:version", "Old:4:title+aliases+content");
    return current;
  }

  void advanceClock(Duration d) {
    clock.advance(d);
  }

  Instant now() {
    return clock.instant();
  }

  // ---- 임베더 행동 ----

  void embedderFails() {
    embedder.fail = true;
  }

  /** 이 조각을 담은 입력의 임베딩만 429로 실패한다 — 그 문서 하나만 실패시킨다. */
  void embedderFailsOnInputContaining(String fragment) {
    embedder.failOn = fragment;
  }

  void embedderRecovers() {
    embedder.fail = false;
    embedder.failOn = null;
  }

  /** 다음 임베딩 중에 같은 소스의 투영 요청이 새로 등록된다. */
  void requestArrivesDuringNextEmbedding() {
    embedder.onNextEmbed(this::requestProjection);
  }

  /** 다음 임베딩이 releaseEmbedding()까지 멈춘다. */
  void holdNextEmbedding() {
    HoldGate gate = new HoldGate(WAIT_LIMIT);
    embedder.gate = gate;
    embedder.onNextEmbed(gate::hold);
  }

  void awaitEmbeddingHeld() {
    embedder.gate.awaitHeld("투영이 임베딩 중");
  }

  void releaseEmbedding() {
    embedder.gate.release();
  }

  /** 임베딩마다 시계가 이만큼 흐르고 heartbeat tick이 돈다. */
  void everyEmbeddingTakes(Duration d) {
    embedder.onEachEmbed =
        () -> {
          clock.advance(d);
          scheduler.tick();
        };
  }

  /** 임베딩마다 heartbeat 없이 점유 TTL이 지난다. */
  void claimExpiresDuringEachEmbedding() {
    embedder.onEachEmbed = () -> clock.advance(LEASE_TTL.plusSeconds(1));
  }

  /** 다음 임베딩 중에 점유가 만료되고 경쟁자(p2)가 투영을 끝낸다. 그 뒤 이 투영이 받는 벡터는 표식값이다. */
  void competitorTakesOverDuringNextEmbedding() {
    ProjectionRun competitor = projections.run("p2", scheduler);
    embedder.onNextEmbed(
        () -> {
          clock.advance(LEASE_TTL.plusSeconds(1));
          competitorReports.addAll(competitor.runDue(CancellationToken.none()));
          embedder.sentinel = true;
        });
  }

  // ---- 투영 ----

  List<ProjectionRun.Report> runDue() {
    return projection.runDue(CancellationToken.none());
  }

  /** due 요청 하나를 투영한 보고(요청이 정확히 하나가 아니면 given 준비 실패). */
  ProjectionRun.Report project() {
    return single(runDue());
  }

  ProjectionRun.Report projectAs(String owner) {
    return single(projections.run(owner, scheduler).runDue(CancellationToken.none()));
  }

  /** heartbeat 등록을 거부하는 스케줄러로 투영한다. */
  ProjectionRun.Report projectWithHeartbeatsRejected(String reason) {
    return single(
        projections
            .run("p1", ManualTaskScheduler.rejecting(reason))
            .runDue(CancellationToken.none()));
  }

  Future<ProjectionRun.Report> projectInBackground() {
    return CompletableFuture.supplyAsync(this::project);
  }

  static ProjectionRun.Report finish(Future<ProjectionRun.Report> report) throws Exception {
    return report.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
  }

  // ---- 관찰 ----

  List<ContextUnit> units() {
    return units.listAll(SPACE);
  }

  List<String> unitIds() {
    return units().stream().map(ContextUnit::id).toList();
  }

  Optional<OutboxPort.Request> request() {
    return adapters.outbox().request(SOURCE);
  }

  List<SourceInstanceId> pending() {
    return adapters.outbox().pending();
  }

  int embedCalls() {
    return embedder.calls.get();
  }

  boolean hasKeywordHits(String query) {
    return !PostgresTestAdapters.keywordIndex(ds)
        .search(SPACE, query, 5, SearchFilter.NONE)
        .isEmpty();
  }

  Map<String, double[]> vectors() {
    return PostgresTestAdapters.vectorIndex(ds).vectorsOf(SPACE, expectedUnitIds());
  }

  Optional<String> embeddingGeneration() {
    return PostgresTestAdapters.spaceMeta(ds).get(SPACE, "embedding:version");
  }

  /** 검색 레시피(search_context)의 1위 문서. */
  Optional<String> topDocumentFor(String question) {
    var search =
        new QuestionSearch(
            units,
            PostgresTestAdapters.keywordIndex(ds),
            PostgresTestAdapters.vectorIndex(ds),
            embedder,
            Reranker.DISABLED,
            SearchSettings.DEFAULT,
            clock,
            TestMetrics.search(),
            TestExecutors.CALLER_THREAD);
    return search.search(AccessScope.singleUser(), SPACE, question).candidates().stream()
        .findFirst()
        .map(c -> c.unit().documentId());
  }

  boolean holdsActiveProjectionClaim() {
    return adapters
        .claims()
        .current(ProjectionRun.projectionKey(SOURCE))
        .orElseThrow()
        .isActiveAt(clock.instant());
  }

  List<ProjectionRun.Report> competitorReports() {
    return List.copyOf(competitorReports);
  }

  MeterRegistry metrics() {
    return metrics;
  }

  @Override
  public void close() {
    if (embedder.gate != null) embedder.gate.release();
    try {
      slack.assertNoUnmatched();
    } finally {
      slack.close();
    }
  }

  // ---- 연결 ----

  private static ProjectionRun.Report single(List<ProjectionRun.Report> reports) {
    if (reports.size() != 1)
      throw new IllegalStateException("given 준비 실패: 투영 요청이 1건이어야 한다 — " + reports.size() + "건");
    return reports.get(0);
  }

  private static List<JsonNode> expectedUnits() {
    try {
      List<JsonNode> out = new ArrayList<>();
      JSON.readTree(Files.readString(FIXTURES.resolve("expected-units.json")))
          .get("units")
          .forEach(out::add);
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
