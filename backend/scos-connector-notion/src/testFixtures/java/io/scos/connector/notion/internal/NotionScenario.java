package io.scos.connector.notion.internal;

import io.scos.collection.internal.RawContentCapture;
import io.scos.collection.internal.TsRawViews;
import io.scos.connector.notion.NotionAdapter;
import io.scos.connector.notion.NotionAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.SourceConfig;
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
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Notion 어댑터 fixture 테스트의 연결(TESTING §3.6 3층): 가짜 Notion(TS 데이터셋), 기다리지 않는 공급자 실행기(대기는 기록만), 고정 시각,
 * TS 기대 결과 읽기. 본문은 "어떤 설정·체크포인트·장애로 걸으면 무엇이 나오는가"만 쓴다. 닫을 때 데이터셋 밖 요청이 있으면 실패한다.
 */
final class NotionScenario implements AutoCloseable {
  static final Instant NOW = Instant.parse(NotionFixtures.dataset().get("now").asString());

  /** 장애 run의 시도 timeout(TS 빠른 정책 50ms에 해당 — 응답하지 않는 요청을 끊는다). */
  static final Duration FAST_TIMEOUT = Duration.ofMillis(300);

  /** 설정 이름(notion-dataset.json configs): 운영 roots, roots + discoverSharedPages, 검색, 공유되지 않은 루트. */
  enum Config {
    ROOTS("roots"),
    DISCOVER("discover"),
    SEARCH("search"),
    MISSING_ROOT("missingRoot");

    final String name;

    Config(String name) {
      this.name = name;
    }

    String json() {
      return NotionFixtures.config(name);
    }

    /** 표의 설정({schemaVersion, value})이 가리키는 데이터셋 설정. */
    static Config of(JsonNode config) {
      for (Config c : values())
        if (NotionFixtures.JSON.readTree(c.json()).equals(config.get("value"))) return c;
      throw new IllegalArgumentException("데이터셋에 없는 설정: " + config);
    }
  }

  private final FakeNotionServer notion;
  private final MutableClock clock = new MutableClock(NOW);
  private final List<Duration> waits = new CopyOnWriteArrayList<>();
  private Duration requestTimeout = ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT;
  private Optional<String> unreachableBaseUrl = Optional.empty();
  private Optional<String> credential = Optional.empty();
  private final CancellationToken cancel = new CancellationToken();

  NotionScenario() {
    try {
      notion = new FakeNotionServer(NotionFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---- 장애·갈래 ----

  void phase(FakeNotionServer.Phase phase) {
    notion.phase(phase);
  }

  void crashAt(JsonNode request) {
    notion.crashAt(request);
  }

  /** TS 장애 run 하나(expected-failures.json cases[i])의 각본을 건다: faults, 전송 실패면 닫힌 포트, 빠른 시도 timeout. */
  void failureCase(JsonNode failure) {
    failure.get("faults").forEach(notion::fault);
    requestTimeout = FAST_TIMEOUT;
    if (failure.get("transportDown").asBoolean())
      unreachableBaseUrl = Optional.of(NotionAssembly.closedPort());
  }

  /**
   * TS 표의 첫 공급자 호출(expected-client.json checkpointCases의 firstRequest)이 보낼 HTTP 요청에 인증 실패로 답해 수집을
   * 거기서 멈춘다. 그 요청을 돌려준다.
   */
  NotionTsRuns.RequestView stopAt(JsonNode tsCall) {
    NotionTsRuns.RequestView expected = NotionTsRuns.RequestView.ofTsCall(tsCall);
    notion.respond(
        expected.method(),
        expected.path(),
        new Response(401, FakeNotionServer.UNAUTHORIZED, Map.of()));
    return expected;
  }

  /** TS 장애 run 하나(expected-failures.json cases의 label). */
  static JsonNode tsFailure(String label) {
    for (JsonNode c : NotionFixtures.read("expected-failures.json").get("cases"))
      if (c.get("label").asString().equals(label)) return c;
    throw new IllegalArgumentException("given 준비 실패: TS 장애 run이 없다 — " + label);
  }

  /** 이 목록 경로(검색 "POST /v1/search", 블록 자식 "GET /v1/blocks/<id>/children")의 다음 커서가 A → B → A로 돈다. */
  void withCyclingCursor(String method, String path, String type) {
    notion.cycleCursor(method, path, type);
  }

  /** 이 목록 경로가 요청한 커서를 다음 커서로 다시 준다(A → A). */
  void withRepeatedCursor(String method, String path, String type) {
    notion.repeatCursor(method, path, type);
  }

  /** 이 목록 경로는 요청 커서(첫 페이지는 없음)에 따라 이 각본으로 답한다(데이터셋보다 앞선다). */
  void script(String method, String path, Function<Optional<String>, Response> answer) {
    notion.script(method, path, answer);
  }

  /** 수집을 이미 취소된 토큰으로 시작한다. */
  void cancelBeforeStart() {
    cancel.cancel();
  }

  /** hang 장애 각본의 요청이 걸리면(서버가 답하지 않기 시작하면) 수집 토큰을 취소한다. */
  void cancelWhenHanging() {
    notion.onHang(cancel::cancel);
  }

  /** 인터럽트를 받은 수집의 결과와, 수집이 돌아온 뒤 수집 스레드의 인터럽트 표시가 남아 있었는가. */
  record Interrupted(Collection collection, boolean stillInterrupted) {}

  /**
   * hang 장애 각본의 요청이 걸리면 수집 스레드(부른 스레드)를 인터럽트하고 그 수집을 끝까지 돌린다 — 응답을 기다리는 중의 인터럽트(데몬 stop). 끝나면 표시를
   * 읽으면서 지운다(다음 테스트로 새지 않게).
   */
  Interrupted collectTraversalInterruptedWhileHanging(Config config) {
    Thread collector = Thread.currentThread();
    notion.onHang(collector::interrupt);
    Collection collection = collectTraversal(config);
    return new Interrupted(collection, Thread.interrupted());
  }

  /** 자격 증명 참조가 이 값으로 풀린다(기본은 데이터셋 토큰) — 헤더에 실을 수 없는 값 각본. */
  void credentialResolvesTo(String value) {
    credential = Optional.of(value);
  }

  String token() {
    return notion.token();
  }

  // ---- 수집 ----

  Collection collect(Config config) {
    return collect(config, Optional.empty());
  }

  Collection collectFrom(Config config, Checkpoint checkpoint) {
    return collect(config, Optional.of(checkpoint));
  }

  Collection collect(Config config, Optional<Checkpoint> checkpoint) {
    return collect(config.json(), checkpoint);
  }

  /** 데이터셋 밖 설정(표의 설정 값 JSON)으로 수집한다. */
  Collection collect(String configJson, Optional<Checkpoint> checkpoint) {
    Collection out = new Collection();
    try {
      adapter()
          .collect(
              new CollectionInput(
                  NotionAssembly.instance(configJson, NOW).descriptor(),
                  checkpoint,
                  CollectionMode.INCREMENTAL,
                  executor(),
                  RawContentCapture.defaults(),
                  cancel),
              out.pages::add);
    } catch (RuntimeException e) {
      out.failure = e;
    }
    return out;
  }

  Collection collectTraversal(Config config) {
    return collectTraversal(config.json(), Optional.empty());
  }

  Collection collectTraversalFrom(Config config, Checkpoint checkpoint) {
    return collectTraversal(config.json(), Optional.of(checkpoint));
  }

  /** v3 순회 소유자의 TS 요청/페이지 계약. v4 실제 어댑터 계약은 런타임 suite가 검증한다. */
  Collection collectTraversal(String configJson, Optional<Checkpoint> checkpoint) {
    var config = NotionConfiguration.read(NotionFixtures.JSON, new SourceConfig(1, configJson));
    if (!(config instanceof NotionConfiguration.Roots roots))
      return collect(configJson, checkpoint);
    var out = new Collection();
    try {
      var input =
          new CollectionInput(
              NotionAssembly.instance(configJson, NOW).descriptor(),
              checkpoint,
              CollectionMode.INCREMENTAL,
              executor(),
              RawContentCapture.defaults(),
              cancel);
      var traversal = NotionRootTraversal.resume(NotionFixtures.JSON, roots, checkpoint);
      var client =
          NotionAssembly.client(unreachableBaseUrl.orElse(notion.baseUrl()), requestTimeout);
      new NotionRootCollection(
              roots,
              traversal,
              client,
              new NotionCalls(input, clock),
              new NotionMutations(input.content(), clock),
              CredentialResolver.headerValue(credential.orElse(notion.token())))
          .collect(out.pages::add);
    } catch (RuntimeException failure) {
      out.failure = failure;
    }
    return out;
  }

  CollectionCoverage coverage(JsonNode configValue, Optional<Checkpoint> checkpoint) {
    return adapter()
        .coverage(
            new SourceConfig(1, configValue.toString()), CollectionMode.INCREMENTAL, checkpoint);
  }

  // ---- 관찰 ----

  List<Duration> waits() {
    return List.copyOf(waits);
  }

  List<NotionTsRuns.RequestView> requests() {
    return notion.requests().stream().map(NotionTsRuns.RequestView::of).toList();
  }

  private ProviderCallExecutor executor() {
    return NotionAssembly.recordingExecutor(requestTimeout, clock, waits);
  }

  private NotionAdapter adapter() {
    return NotionAssembly.adapter(
        unreachableBaseUrl.orElse(notion.baseUrl()),
        credential.orElse(notion.token()),
        clock,
        requestTimeout);
  }

  @Override
  public void close() {
    notion.close();
  }

  // ---- 결과·비교 값 ----

  static final class Collection {
    private final List<CollectionPage> pages = new ArrayList<>();
    private RuntimeException failure;

    List<NotionTsRuns.PageView> pageViews() {
      return pages.stream().map(NotionTsRuns.PageView::of).toList();
    }

    /** 외부 id별 마지막 upsert(같은 run에서 지운 원본은 빠진다). */
    List<TsRawViews.ItemView> lastItems() {
      return TsRawViews.lastItems(pages);
    }

    List<TsRawViews.RejectionView> rejections() {
      return pages.stream()
          .flatMap(p -> p.rejections().stream())
          .map(TsRawViews.RejectionView::of)
          .toList();
    }

    Optional<RuntimeException> failure() {
      return Optional.ofNullable(failure);
    }

    /** 부재(요청 없음)를 보기 전에 수집이 실패했음을 확보한다 — 성공했으면 given 준비 실패. */
    Collection requireFailed() {
      if (failure == null)
        throw new IllegalStateException("given 준비 실패: 수집이 실패해야 한다 — 페이지 " + pages.size());
      return this;
    }

    /** 공급자 호출 실패로 끝났으면 그 실패 종류. */
    Optional<CallFailure.Kind> failureKind() {
      return failure()
          .filter(CallFailureException.class::isInstance)
          .map(f -> ((CallFailureException) f).failure().kind());
    }

    /** 공급자 호출 실패로 끝났으면 그 실패 메시지 — run의 errorSummary와 collection.run.failed 이벤트에 그대로 남는다. */
    Optional<String> failureMessage() {
      return failure()
          .filter(CallFailureException.class::isInstance)
          .map(f -> ((CallFailureException) f).failure().message());
    }

    /** 공급자 호출 실패로 끝났으면 그 위치(JSON) — collection.run.failed 이벤트의 providerLocator가 된다. */
    Optional<JsonNode> failureLocator() {
      return failure()
          .filter(CallFailureException.class::isInstance)
          .flatMap(f -> ((CallFailureException) f).providerLocatorJson())
          .map(NotionFixtures.JSON::readTree);
    }

    /** 공급자 호출 실패로 끝났으면 그 응답 검사 위반 — collection.run.failed 이벤트의 violation이 된다. */
    Optional<CallFailure.Violation> failureViolation() {
      return failure()
          .filter(CallFailureException.class::isInstance)
          .flatMap(f -> ((CallFailureException) f).failure().violation());
    }

    /** 이 실패로 run에 남을 errorKind — 제품 매핑(CONTRACTS "run errorKind 매핑") 그대로. 실패가 없으면 비어 있다. */
    Optional<String> errorKind() {
      return failure().map(TsRawViews::runErrorKind);
    }
  }
}
