package io.scos.connector.googledrive.internal;

import io.scos.collection.internal.RawContentCapture;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.TsRawViews.ItemView;
import io.scos.connector.googledrive.GoogleDriveAdapter;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.googledrive.internal.GoogleDriveViews.ErrorView;
import io.scos.connector.googledrive.internal.GoogleDriveViews.PageView;
import io.scos.connector.googledrive.internal.GoogleDriveViews.RequestView;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.support.FakeHttpServer.Response;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;

/**
 * Drive 어댑터 fixture 테스트의 연결(TESTING §3.6 3층): 가짜 Google(TS 데이터셋), 기다리지 않는 공급자 실행기(대기는 기록만), 고정 시각,
 * TS 기대 결과 읽기. 본문은 "어떤 체크포인트·응답·상한으로 걸으면 무엇이 나오는가"만 쓴다. 닫을 때 데이터셋 밖 요청이 있으면 실패한다.
 */
final class GoogleDriveScenario implements AutoCloseable {
  static final Instant NOW = Instant.parse(GoogleDriveFixtures.dataset().get("now").asString());

  /** 한 run의 페이지 상한 — 큐가 줄지 않는 결함(변이)이 끝없이 페이지를 내면 수집을 실패시킨다. fixture run은 수십 쪽이다. */
  static final int MAX_PAGES = 1000;

  private final FakeGoogleServer google;
  private final MutableClock clock = new MutableClock(NOW);
  private final List<Duration> waits = new CopyOnWriteArrayList<>();
  private RawContentCapture capture = RawContentCapture.defaults();
  private String configJson = GoogleDriveAssembly.SYNTHETIC_ACCOUNT;
  private String credentialJson = GoogleDriveFixtures.credentialJson();
  private CancellationToken cancel = CancellationToken.none();
  private boolean cancelAfterFirstPage;
  private boolean clockFlowsWhileWaiting;
  private final ProviderCallExecutor calls =
      new ProviderCallExecutor(
          ProviderCallPolicy.defaults(() -> 0.0),
          clock,
          (d, c) -> {
            waits.add(d);
            if (clockFlowsWhileWaiting) clock.advance(d);
            return c.isCancelled();
          },
          TestMetrics.providerCalls());

  GoogleDriveScenario() {
    try {
      google = new FakeGoogleServer(GoogleDriveFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---- 준비 ----

  /** 두 번째 수집(변경 피드 run)의 데이터셋 응답으로 바꾼다. */
  void givenSecondRunResponses() {
    google.useSecondRunRoutes();
  }

  /** 원본 캡처 상한(inline·전체)을 바꾼다. */
  void captureLimit(int inlineMaxBytes, long maxBytes) {
    capture = new RawContentCapture(inlineMaxBytes, maxBytes);
  }

  /** 이 요청(경로 + 쿼리)에서 run이 끝난다(401). */
  void crashAt(String path, Map<String, String> params) {
    google.crashAt(path, params);
  }

  /** TS 사례 하나의 덮어쓰기(경로마다 응답, 설정·자격 증명·캡처 상한)를 건다. */
  void givenCase(JsonNode c) {
    google.respondAll(c.get("overrides"));
    if (!c.get("config").isNull()) configJson = c.get("config").get("value").toString();
    if (!c.get("credential").isNull()) credentialJson = c.get("credential").asString();
    if (!c.get("captureMaxBytes").isNull())
      capture =
          new RawContentCapture(
              RawContentCapture.DEFAULT_INLINE_MAX, c.get("captureMaxBytes").asLong());
  }

  /** 이 경로(쿼리 무관, 다운로드 제외)는 이 응답으로 답한다. */
  void pathAnswers(String path, Response response) {
    google.respond(path, response);
  }

  /** 이 파일 하나만 남은 큐에서 시작하는 체크포인트(TS resumeFiles). */
  static Checkpoint resumeFile(String fileId) {
    return new Checkpoint(
        1,
        "{\"accountId\":\"perm-reader\",\"pending\":[{\"kind\":\"file\",\"id\":\""
            + fileId
            + "\"}],\"seenFiles\":[\""
            + fileId
            + "\"],\"downloadedVersions\":{},\"changeTokens\":{\"user\":\"saved\"},\"files\":{}}");
  }

  /** 재시도 대기만큼 시계가 흐른다 — 실행기의 경과 예산(10분)이 실제로 작동한다. 기본은 시계가 멈춰 있다(시도 수 상한만 본다). */
  void clockFlowsWhileWaiting() {
    clockFlowsWhileWaiting = true;
  }

  /** TS 데이터셋 모양의 덮어쓰기({origin, path, reply} 목록)를 건다. */
  void givenOverrides(JsonNode overrides) {
    google.respondAll(overrides);
  }

  /** 첫 페이지를 받은 직후 수집을 취소한다(실행 중 취소). */
  void cancelAfterFirstPage() {
    cancel = new CancellationToken();
    cancelAfterFirstPage = true;
  }

  /** 이 파일들을 지우는 작업(공급자 호출 없음)만 남은 큐에서 시작하는 체크포인트. */
  static Checkpoint resumeRemovals(String... fileIds) {
    StringBuilder pending = new StringBuilder();
    for (String id : fileIds)
      pending
          .append(pending.isEmpty() ? "" : ",")
          .append("{\"kind\":\"remove\",\"id\":\"")
          .append(id)
          .append("\"}");
    return new Checkpoint(
        1,
        "{\"accountId\":\"perm-reader\",\"pending\":["
            + pending
            + "],\"seenFiles\":[],\"downloadedVersions\":{},\"changeTokens\":{\"user\":\"saved\"},\"files\":{}}");
  }

  /** 수집을 이미 취소된 토큰으로 시작한다. */
  void cancelBeforeStart() {
    cancel = new CancellationToken();
    cancel.cancel();
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
              page -> {
                if (out.pages.size() == MAX_PAGES)
                  throw new IllegalStateException("페이지 상한을 넘었다: 큐가 줄지 않는다");
                out.pages.add(page);
                if (cancelAfterFirstPage) cancel.cancel();
              });
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

  /** 가짜 Google이 받은 요청(origin·경로·디코드한 쿼리). */
  List<RequestView> requests() {
    return google.requests().stream().map(RequestView::of).toList();
  }

  private GoogleDriveAdapter adapter() {
    return GoogleDriveAssembly.adapter(google.baseUrl(), credentialJson, clock);
  }

  private SourceInstance instance() {
    return GoogleDriveAssembly.instance(configJson, NOW);
  }

  @Override
  public void close() {
    google.close();
  }

  // ---- 결과·비교 값 ----

  static final class Collection {
    private final List<CollectionPage> pages = new ArrayList<>();
    private RuntimeException failure;

    List<PageView> pageViews() {
      return pages.stream().map(PageView::of).toList();
    }

    /** 페이지 변경을 차례로 적용한 뒤 남은 upsert(외부 id별 마지막). */
    List<ItemView> activeItems() {
      Map<String, RawItem> active = new LinkedHashMap<>();
      for (CollectionPage p : pages)
        for (RawMutation m : p.mutations()) {
          active.remove(m.externalId());
          if (m instanceof RawMutation.Upsert u) active.put(m.externalId(), u.item());
        }
      return active.values().stream().map(ItemView::of).toList();
    }

    /** 페이지 순서대로 낸 upsert 원본 전부(같은 id가 두 번이면 두 번). */
    List<ItemView> upsertedItems() {
      return pages.stream()
          .flatMap(p -> p.mutations().stream())
          .filter(m -> m instanceof RawMutation.Upsert)
          .map(m -> ItemView.of(((RawMutation.Upsert) m).item()))
          .toList();
    }

    /** upsert 원본마다 본문의 수집 제한(limitations, 원문 순서) — 페이지 순서대로. */
    List<List<String>> limitations() {
      return upsertedItems().stream()
          .map(
              i -> {
                List<String> out = new ArrayList<>();
                GoogleDriveFixtures.JSON
                    .readTree(i.content().valueJson())
                    .path("limitations")
                    .forEach(l -> out.add(l.asString()));
                return List.copyOf(out);
              })
          .toList();
    }

    /** 마지막 페이지의 체크포인트. */
    Optional<JsonNode> lastCheckpoint() {
      return pages.isEmpty()
          ? Optional.empty()
          : Optional.of(GoogleDriveViews.checkpointJson(pages.getLast().nextCheckpoint()));
    }

    Optional<RuntimeException> failure() {
      return Optional.ofNullable(failure);
    }

    /** 실패의 비교 값(TS 오류 이름·메시지·분류). */
    Optional<ErrorView> error() {
      return failure().map(ErrorView::of);
    }

    /** 실패 분류의 kind(wire 값). */
    Optional<String> failureKind() {
      return error().map(ErrorView::failure).map(f -> f.get("kind").asString());
    }
  }
}
