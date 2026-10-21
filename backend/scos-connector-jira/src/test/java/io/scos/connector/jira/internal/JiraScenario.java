package io.scos.connector.jira.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.RawContentCapture;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.TsRawViews;
import io.scos.collection.internal.TsRawViews.ItemView;
import io.scos.collection.internal.TsRawViews.RejectionView;
import io.scos.connector.jira.JiraAdapter;
import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.jira.internal.JiraTsRuns.PageView;
import io.scos.connector.jira.internal.JiraTsRuns.RequestView;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jira 어댑터 fixture 테스트의 연결(TESTING §3.6 3층): 가짜 Jira(TS 데이터셋), 기다리지 않는 공급자 실행기(대기는 기록만), 고정 시각, TS
 * 기대 결과 읽기. 본문은 "어떤 체크포인트·장애로 걸으면 무엇이 나오는가"만 쓴다. 닫을 때 데이터셋 밖 요청이 있으면 실패한다.
 */
final class JiraScenario implements AutoCloseable {
  static final Instant NOW = Instant.parse(JiraFixtures.dataset().get("now").asString());

  private final FakeJiraServer jira;
  private final MutableClock clock = new MutableClock(NOW);
  private final List<Duration> waits = new CopyOnWriteArrayList<>();
  private RawContentCapture capture = RawContentCapture.defaults();
  private CancellationToken cancel = CancellationToken.none();
  private Optional<String> credential = Optional.empty();
  private String sourceConfig = JiraAssembly.SYNTHETIC_SITE;
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final ProviderCallExecutor calls =
      new ProviderCallExecutor(
          ProviderCallPolicy.defaults(() -> 0.0),
          clock,
          (d, c) -> {
            waits.add(d);
            return c.isCancelled();
          },
          ProviderCallMetrics.of(metrics));

  JiraScenario() {
    try {
      jira = new FakeJiraServer(JiraFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static final String SITE = JiraFixtures.dataset().get("siteUrl").asString();

  /** TS checkpointWith: 이 사이트의 v1 체크포인트(작업 큐 JSON 배열, 내려받은 첨부 id 배열). */
  static Checkpoint checkpointWith(String pendingJson, String downloadedJson) {
    return new Checkpoint(
        1,
        "{\"siteUrl\":\""
            + SITE
            + "\",\"pending\":"
            + pendingJson
            + ",\"downloadedAttachments\":"
            + downloadedJson
            + "}");
  }

  // ---- 장애 ----

  /** 이 경로(쿼리 무관)는 늘 이 상태로 답한다. */
  void pathAnswers(String path, int status) {
    jira.respond(path, new Response(status, "{}", Map.of()));
  }

  /** 이 이슈 경로(쿼리 무관)는 이 JSON으로 답한다 — 원격 링크 등 나머지는 데이터셋. */
  void issueAnswers(String path, String issueJson) {
    jira.respond(path, new Response(200, issueJson, Map.of("Content-Type", "application/json")));
  }

  String kind() {
    return adapter().kind();
  }

  void failNext(String pathPrefix, int times, int status) {
    jira.failNext(pathPrefix, times, status);
  }

  /** 원본 캡처 상한(기본 50 MiB)을 바꾼다 — 첨부 다운로드 상한도 이 값이다. */
  void captureLimit(long maxBytes) {
    capture = new RawContentCapture(RawContentCapture.DEFAULT_INLINE_MAX, maxBytes);
  }

  /** 데이터셋 자격 증명의 API 토큰. */
  String token() {
    return jira.token();
  }

  /** 자격 증명 참조가 이 값으로 풀린다(데이터셋 토큰 대신). */
  void credentialResolvesTo(String value) {
    credential = Optional.of(value);
  }

  /** 소스 설정에서 첨부 다운로드를 끈다(downloadAttachments:false, 나머지는 데이터셋 설정). */
  void attachmentDownloadsOff() {
    ObjectNode config = (ObjectNode) JiraFixtures.dataset().get("config").get("value").deepCopy();
    sourceConfig = config.put("downloadAttachments", false).toString();
  }

  /** 수집을 이미 취소된 토큰으로 시작한다. */
  void cancelBeforeStart() {
    cancel = new CancellationToken();
    cancel.cancel();
  }

  /** 인터럽트를 받은 수집의 결과와, 수집이 돌아온 뒤 수집 스레드의 인터럽트 표시가 남아 있었는가. */
  record Interrupted(Collection collection, boolean stillInterrupted) {}

  /**
   * 이 경로의 요청이 들어오면 수집 스레드(부른 스레드)를 인터럽트하고 그 수집을 끝까지 돌린다. 요청은 그대로 답한다 — 배치 결과를 기다리는 중의 인터럽트를 결정적으로
   * 만든다(그 요청의 결과가 나오기 전에 플래그가 선다). 끝나면 표시를 읽으면서 지운다(다음 테스트로 새지 않게).
   */
  Interrupted collectInterruptedOnRequest(String pathContains, Checkpoint checkpoint) {
    Thread collector = Thread.currentThread();
    jira.gateOn(pathContains, new CountDownLatch(0), path -> collector.interrupt());
    Collection collection = collectFrom(checkpoint);
    return new Interrupted(collection, Thread.interrupted());
  }

  /** 이 경로의 요청은 n개가 동시에 들어와야 답한다(상한까지). 동시에 들어온 최대 수를 기록한다. */
  void pathAnswersWhenInFlight(String pathPrefix, int n) {
    jira.holdUntilInFlight(pathPrefix, n);
  }

  // ---- 수집 ----

  Collection collect() {
    return collect(Optional.empty());
  }

  Collection collectFrom(Checkpoint checkpoint) {
    return collect(Optional.of(checkpoint));
  }

  Collection collect(Optional<Checkpoint> checkpoint) {
    Collection out = new Collection();
    try {
      adapter()
          .collect(
              new CollectionInput(
                  instance().descriptor(),
                  checkpoint,
                  CollectionMode.INCREMENTAL,
                  calls,
                  capture,
                  cancel),
              out.pages::add);
    } catch (RuntimeException e) {
      out.failure = e;
    }
    return out;
  }

  CollectionCoverage coverage(CollectionMode mode) {
    return adapter().coverage(instance().config(), mode, Optional.empty());
  }

  // ---- 관찰 ----

  List<Duration> waits() {
    return List.copyOf(waits);
  }

  /** 가짜 Jira가 받은 요청(경로 + 디코드한 쿼리). */
  List<RequestView> requests() {
    return jira.requests().stream().map(RequestView::of).toList();
  }

  /** 부재(다시 받지 않음)를 보기 전에 이 경로를 요청했음을 확보한다 — 요청하지 않았으면 given 준비 실패. */
  void requireRequested(String path) {
    if (requests().stream().noneMatch(r -> r.path().equals(path)))
      throw new IllegalStateException("given 준비 실패: " + path + "를 요청해야 한다 — " + requests());
  }

  /** 공급자 호출 지표가 쌓이는 레지스트리(`assertThatMetrics`로 단언한다). */
  MeterRegistry metrics() {
    return metrics;
  }

  int maxInFlight() {
    return jira.maxInFlight();
  }

  private JiraAdapter adapter() {
    return JiraAssembly.adapter(jira.baseUrl(), credential.orElse(jira.token()), clock);
  }

  private SourceInstance instance() {
    return JiraAssembly.instance(sourceConfig, NOW);
  }

  @Override
  public void close() {
    jira.close();
  }

  // ---- 결과·비교 값 ----

  static final class Collection {
    private final List<CollectionPage> pages = new ArrayList<>();
    private RuntimeException failure;

    List<CollectionPage> pages() {
      return List.copyOf(pages);
    }

    List<PageView> pageViews() {
      return pages.stream().map(PageView::of).toList();
    }

    /** 외부 id별 마지막 upsert. */
    List<ItemView> lastItems() {
      return TsRawViews.lastItems(pages);
    }

    List<RejectionView> rejections() {
      return pages.stream().flatMap(p -> p.rejections().stream()).map(RejectionView::of).toList();
    }

    Optional<RuntimeException> failure() {
      return Optional.ofNullable(failure);
    }

    /** 부재(요청 없음·거부 없음)를 보기 전에 수집이 실패했음을 확보한다 — 성공했으면 given 준비 실패. */
    Collection requireFailed() {
      if (failure == null)
        throw new IllegalStateException("given 준비 실패: 수집이 실패해야 한다 — 페이지 " + pages.size());
      return this;
    }

    /** 마지막 페이지 체크포인트의 다운로드 이력(받은 첨부 id, 순서대로). */
    List<String> downloadHistory() {
      List<String> ids = new ArrayList<>();
      JiraTsRuns.checkpointJson(pages.getLast().nextCheckpoint())
          .get("value")
          .get("downloadedAttachments")
          .forEach(n -> ids.add(n.asString()));
      return ids;
    }

    /** 공급자 호출 실패로 끝났으면 그 분류 종류. */
    Optional<CallFailure.Kind> failureKind() {
      return failure()
          .filter(CallFailureException.class::isInstance)
          .map(f -> ((CallFailureException) f).failure().kind());
    }
  }
}
