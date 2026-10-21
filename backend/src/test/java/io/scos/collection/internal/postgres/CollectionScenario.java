package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStore;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.RequestGate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

/**
 * Slack 수집 런타임 DB 테스트의 연결을 소유한다: 가짜 Slack·실제 런타임(owner별)·가짜 시계·수동 heartbeat 스케줄러. 본문은 "무엇을 바꾸고 어떤
 * worker가 수집하면 무엇이 남는가"만 쓴다. 만들 때 DB를 비운다(TESTING §6.1). 종류와 무관한 부분 — DB 관찰 ({@link
 * PostgresCollectionObservations}), 백그라운드 수집({@link BackgroundCollection}), 요청 게이트({@link
 * RequestGate}) — 은 공용이고 여기서는 한 줄로 넘긴다. Slack 쪽은 데이터셋 변경·장애 각본·소스 설정뿐이다(TESTING §3.6).
 */
@Tag("db")
final class CollectionScenario implements AutoCloseable {
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"));
  static final SourceInstanceId SOURCE = SlackAssembly.SOURCE;
  static final Duration TTL = Duration.ofSeconds(30);
  static final String MAIN_CHANNEL = "C0000000001";

  /** 스레드 replies 둘째 페이지 요청 — 여기서 멈추면 history 1페이지 + replies 1페이지가 이미 커밋돼 있다. */
  static final String SECOND_REPLIES_PAGE = "cursor=page:1";

  static final String HISTORY = "conversations.history";
  private static final int NO_OVERLAP = 0;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
  private final AtomicInteger runs = new AtomicInteger();
  private final DataSource ds;
  private final PostgresCollectionObservations observe;
  private final FakeSlackServer slack;
  private final RequestGate gate;

  CollectionScenario() {
    PostgresSupport.truncateAll();
    ds = PostgresSupport.dataSource();
    observe = new PostgresCollectionObservations(ds, SOURCE, clock);
    try {
      slack = new FakeSlackServer(FIXTURES.resolve("slack-dataset.json"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    gate = new RequestGate(slack::gateOn, BackgroundCollection.WAIT_LIMIT);
    historyOverlapSeconds(NO_OVERLAP);
  }

  // ---- 소스·Slack 변경 ----

  void historyOverlapSeconds(int seconds) {
    PostgresCollectionConfiguration.adapters(ds)
        .sources()
        .save(
            SlackAssembly.instance(
                "{\"conversationTypes\":[\"public_channel\"],\"historyOverlapSeconds\":"
                    + seconds
                    + "}",
                clock.instant()));
  }

  /** ts 없는 메시지(거부 대상)를 데이터셋에서 지운다 — 그 뒤 수집은 거부 0이다. 지울 것이 없으면 given 준비 실패로 멈춘다. */
  void givenNoRejectedItems() {
    if (slack.deleteMessage("C0000000002", "") == 0)
      throw new IllegalStateException("given 준비 실패: 데이터셋 C0000000002에 ts 없는 메시지가 없다");
  }

  void slackMessageEdited(String ts, String newText, String editedTs) {
    slack.editMessage(MAIN_CHANNEL, ts, newText, editedTs);
  }

  void slackMessageDeleted(String ts) {
    if (slack.deleteMessage(MAIN_CHANNEL, ts) == 0)
      throw new IllegalStateException("given 준비 실패: " + MAIN_CHANNEL + "에 ts " + ts + " 메시지가 없다");
  }

  void slackMessageAdded(String text, String ts) {
    slack.addMessage("C0000000002", 0, "U09", text, ts);
  }

  /** 다음 replies 요청 n번이 500으로 실패한다. */
  void repliesFailNext(int times) {
    slack.failNext("/api/conversations.replies", times, FakeSlackServer.Fault.serverError(500));
  }

  // ---- 요청 게이트·시계 ----

  /** 경로에 문자열이 포함된 첫 요청을 releaseRequest()까지 붙잡는다. */
  void holdRequest(String pathContains) {
    gate.hold(pathContains);
  }

  void awaitRequestHeld() throws InterruptedException {
    gate.awaitHeld("붙잡을 요청이 게이트에 옴");
  }

  void releaseRequest() {
    gate.release();
  }

  /** 붙잡아 둔 요청을 놓고 이후 요청은 붙잡지 않는다. */
  void stopHoldingRequests() {
    gate.stopHolding();
  }

  void advanceClock(Duration d) {
    clock.advance(d);
  }

  /** heartbeat 스케줄러를 한 번 돌린다. */
  void tickHeartbeats() {
    scheduler.tick();
  }

  // ---- 수집 ----

  CollectionRuntime.Report collect(String owner) {
    return runtime(owner).collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  CollectionRuntime.Report collectSnapshot(String owner) {
    return runtime(owner).collect(SOURCE, CollectionMode.SNAPSHOT, CancellationToken.none());
  }

  /** 이 DataSource(상한이 다른 풀·연결)로 조립한 런타임으로 수집한다. */
  CollectionRuntime.Report collectUsing(DataSource dataSource) {
    return runtime("w1", dataSource)
        .collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  Future<CollectionRuntime.Report> collectInBackgroundUsing(DataSource dataSource) {
    return BackgroundCollection.start(
        runtime("w1", dataSource), SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  /** 이 DataSource로 수집할 때 던진 예외(정상 종료면 전제 실패). */
  RuntimeException collectionFailureUsing(DataSource dataSource) {
    try {
      collectUsing(dataSource);
    } catch (RuntimeException e) {
      return e;
    }
    throw new IllegalStateException("given 준비 실패: 수집이 실패해야 한다");
  }

  Future<CollectionRuntime.Report> collectInBackground(String owner, CancellationToken cancel) {
    return BackgroundCollection.start(runtime(owner), SOURCE, CollectionMode.INCREMENTAL, cancel);
  }

  Future<CollectionRuntime.Report> collectInBackground(String owner) {
    return collectInBackground(owner, CancellationToken.none());
  }

  static CollectionRuntime.Report finish(Future<CollectionRuntime.Report> run) throws Exception {
    return BackgroundCollection.finish(run);
  }

  /** 백그라운드 수집이 던진 예외(정상 종료면 전제 실패). */
  static Throwable failureOf(Future<CollectionRuntime.Report> run) throws InterruptedException {
    return BackgroundCollection.failureOf(run);
  }

  // ---- 관찰(PostgresCollectionObservations) ----

  long rawItems() {
    return observe.rawItems();
  }

  Optional<RawRecord> rawItem(String externalId) {
    return observe.rawItem(externalId);
  }

  long tombstones() {
    return observe.tombstones();
  }

  long checkpointRevision() {
    return observe.checkpointRevision();
  }

  boolean projectionRequested() {
    return observe.projectionRequested();
  }

  boolean holdsActiveClaim() {
    return observe.holdsActiveClaim();
  }

  boolean hasClaim() {
    return observe.hasClaim();
  }

  boolean hasActiveClaim() {
    return observe.hasActiveClaim();
  }

  Instant claimExpiresAt() {
    return observe.claimExpiresAt();
  }

  Instant now() {
    return clock.instant();
  }

  Optional<RunId> latestRunId() {
    return observe.latestRunId();
  }

  CollectionRun run(RunId id) {
    return observe.run(id);
  }

  Optional<RunStore.SnapshotCompletion> lastSnapshotCompletion() {
    return observe.lastSnapshotCompletion();
  }

  /** 소스 run의 "상태/오류 종류"(시작 순). */
  List<String> runStatuses() {
    return observe.runStatuses();
  }

  @Override
  public void close() {
    gate.close();
    try {
      slack.assertNoUnmatched();
    } finally {
      slack.close();
    }
  }

  private CollectionRuntime runtime(String owner) {
    return runtime(owner, ds);
  }

  private CollectionRuntime runtime(String owner, DataSource dataSource) {
    return PostgresCollectionRuntime.over(dataSource, clock)
        .adapter(SlackAssembly.adapter(slack.baseUrl(), slack.token(), clock))
        .owner(owner)
        .leaseTtl(TTL)
        .heartbeats(scheduler)
        .runCounter(runs)
        .build();
  }
}
