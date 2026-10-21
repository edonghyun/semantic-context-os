package io.scos.connector.slack.internal;

import io.scos.collection.internal.RawContentCapture;
import io.scos.collection.internal.SourceInstance;
import io.scos.connector.slack.SlackAdapter;
import io.scos.connector.slack.SlackAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
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
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceConfig;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Slack 어댑터 fixture 테스트의 연결을 소유한다: 가짜 Slack 서버(TS 데이터셋), 기다리지 않는 공급자 실행기(대기는 기록만), 소스 설정, TS 기대
 * 결과(expected-raw-items.json) 읽기. 본문은 "어떤 체크포인트·모드·장애로 걸으면 무엇이 나오는가"만 쓴다.
 */
final class SlackScenario implements AutoCloseable {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"));
  static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  static final String PUBLIC_CHANNELS = SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP;
  static final String WRONG_TOKEN = "wrong-token";

  private final FakeSlackServer server;
  private final MutableClock clock = new MutableClock(NOW);
  private final List<Duration> waits = new ArrayList<>();
  private final ProviderCallExecutor calls =
      new ProviderCallExecutor(
          ProviderCallPolicy.defaults(() -> 0.0),
          clock,
          (d, c) -> {
            waits.add(d);
            return c.isCancelled();
          },
          TestMetrics.providerCalls());
  private String token;

  SlackScenario() {
    try {
      server = new FakeSlackServer(FIXTURES.resolve("slack-dataset.json"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    token = server.token();
  }

  // ---- 장애·설정 ----

  void withWrongToken() {
    token = WRONG_TOKEN;
  }

  /** 경로 접두사의 다음 요청 n번이 이 장애로 답한다. */
  void failNext(String path, int times, FakeSlackServer.Fault fault) {
    server.failNext(path, times, fault);
  }

  /** 참여 목록을 받은 뒤 채널이 사라졌다(history가 channel_not_found). */
  void withChannelGone(String channelId) {
    server.removeChannel(channelId);
  }

  /** history를 걸은 뒤 스레드가 사라졌다(replies가 thread_not_found). */
  void withThreadGone(String channelId, String threadTs) {
    server.removeThread(channelId, threadTs);
  }

  // ---- 수집 ----

  Collection collect() {
    return collect(Optional.empty(), CollectionMode.INCREMENTAL, PUBLIC_CHANNELS);
  }

  /** 이어 수집의 앞 단계: 처음부터 수집해 페이지를 냈다(실패하거나 페이지가 없으면 given 준비 실패). */
  Collection givenCollected() {
    Collection first = collect();
    if (first.failure != null || first.pages.isEmpty())
      throw new IllegalStateException(
          "given 준비 실패: 첫 수집이 페이지를 내야 한다 — " + first.failureMessage().orElse("페이지 0"));
    return first;
  }

  Collection collectFrom(Checkpoint checkpoint) {
    return collect(Optional.of(checkpoint), CollectionMode.INCREMENTAL, PUBLIC_CHANNELS);
  }

  Collection snapshotFrom(Checkpoint checkpoint) {
    return collect(Optional.of(checkpoint), CollectionMode.SNAPSHOT, PUBLIC_CHANNELS);
  }

  Collection collectWithConfig(String configJson) {
    return collect(Optional.empty(), CollectionMode.INCREMENTAL, configJson);
  }

  Collection collect(Optional<Checkpoint> checkpoint, CollectionMode mode, String configJson) {
    Collection out = new Collection();
    try {
      adapter()
          .collect(
              new CollectionInput(
                  instance(configJson).descriptor(),
                  checkpoint,
                  mode,
                  calls,
                  RawContentCapture.defaults(),
                  CancellationToken.none()),
              out.pages::add);
    } catch (CallFailureException e) {
      out.failure = e;
    }
    return out;
  }

  CollectionCoverage coverage(CollectionMode mode) {
    return adapter().coverage(instance(PUBLIC_CHANNELS).config(), mode, Optional.empty());
  }

  /** 설정 검증 실패의 종류(통과하면 비어 있다). */
  Optional<CallFailure.Kind> validate(String configJson) {
    try {
      adapter().validateConfig(new SourceConfig(1, configJson));
      return Optional.empty();
    } catch (CallFailureException e) {
      return Optional.of(e.failure().kind());
    }
  }

  // ---- 관찰 ----

  List<Duration> waits() {
    return List.copyOf(waits);
  }

  List<String> requests() {
    return server.requests();
  }

  /** 받은 요청 가운데 mark번째부터의 conversations.history 요청(경로+쿼리, 받은 순서). */
  List<String> historyRequestsSince(int mark) {
    List<String> all = server.requests();
    return all.subList(mark, all.size()).stream()
        .filter(r -> r.startsWith("/api/conversations.history"))
        .toList();
  }

  /** 가짜 Slack이 받은 요청마다의 Authorization 헤더. */
  List<String> authorizations() {
    return server.authorizations();
  }

  /** 데이터셋 토큰의 Bearer 헤더 값. */
  String bearerHeader() {
    return "Bearer " + server.token();
  }

  String datasetToken() {
    return server.token();
  }

  @Override
  public void close() {
    try {
      server.assertNoUnmatched();
    } finally {
      server.close();
    }
  }

  private SlackAdapter adapter() {
    return SlackAssembly.adapter(server.baseUrl(), token, clock);
  }

  private static SourceInstance instance(String configJson) {
    return SlackAssembly.instance(configJson, NOW);
  }

  /** 한 번의 수집: 페이지들 또는 실패. */
  static final class Collection {
    private final List<CollectionPage> pages = new ArrayList<>();
    private CallFailureException failure;

    List<CollectionPage> pages() {
      return List.copyOf(pages);
    }

    /** 페이지에 실린 upsert 수(delete는 세지 않는다 — TS report의 upserted + unchanged와 같은 셈). */
    int upserts() {
      return (int)
          pages.stream()
              .flatMap(p -> p.mutations().stream())
              .filter(m -> m instanceof RawMutation.Upsert)
              .count();
    }

    /** 페이지에 실린 변경 수(upsert와 delete 모두). */
    int mutations() {
      return pages.stream().mapToInt(p -> p.mutations().size()).sum();
    }

    /** 외부 id별 마지막 변경을 차례로 적용해 남은 upsert 항목을 비교용 값으로(뒤에 지운 id는 빠진다). */
    List<ItemView> lastItems() {
      Map<String, RawItem> last = new LinkedHashMap<>();
      for (CollectionPage p : pages)
        for (RawMutation m : p.mutations())
          if (m instanceof RawMutation.Upsert u) last.put(m.externalId(), u.item());
          else last.remove(m.externalId());
      return last.values().stream().map(ItemView::of).toList();
    }

    List<RejectedItem> rejections() {
      return pages.stream().flatMap(p -> p.rejections().stream()).toList();
    }

    Checkpoint firstCheckpoint() {
      return pages.get(0).nextCheckpoint();
    }

    Checkpoint finalCheckpoint() {
      return pages.get(pages.size() - 1).nextCheckpoint();
    }

    Optional<CallFailure.Kind> failureKind() {
      return Optional.ofNullable(failure).map(f -> f.failure().kind());
    }

    Optional<String> failureMessage() {
      return Optional.ofNullable(failure).map(Throwable::getMessage);
    }
  }

  /** 항목 하나의 비교용 값(TS 기대 JSON과 같은 필드). */
  record ItemView(
      String externalId,
      String resourceType,
      String sourceVersion,
      String orderScheme,
      String orderPosition,
      JsonNode metadata,
      String mediaType,
      JsonNode value,
      Instant observedAt) {
    static ItemView of(RawItem a) {
      RawContent.Inline c = (RawContent.Inline) a.content().orElseThrow();
      return new ItemView(
          a.externalId(),
          a.resourceType(),
          a.sourceVersion(),
          a.sourceOrder().orElseThrow().scheme(),
          a.sourceOrder().orElseThrow().position(),
          JSON.readTree(a.metadataJson()),
          c.mediaType(),
          JSON.readTree(c.valueJson()),
          a.observedAt());
    }

    static ItemView expected(JsonNode e) {
      return new ItemView(
          e.get("key").get("externalId").asString(),
          e.get("resourceType").asString(),
          e.get("sourceVersion").asString(),
          e.get("sourceOrder").get("scheme").asString(),
          e.get("sourceOrder").get("position").asString(),
          e.get("metadata"),
          e.get("content").get("mediaType").asString(),
          e.get("content").get("value"),
          NOW);
    }
  }

  /** TS SlackAdapter가 같은 데이터셋에서 낸 결과(expected-raw-items.json). */
  static final class TsCollection {
    private final JsonNode root;

    TsCollection() {
      try {
        root = JSON.readTree(Files.readString(FIXTURES.resolve("expected-raw-items.json")));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    int pagesApplied() {
      return root.get("report").get("pagesApplied").asInt();
    }

    /** 페이지에 실린 upsert 수(새 항목 + 바뀌지 않은 항목). */
    int pageUpserts() {
      return root.get("report").get("upserted").asInt()
          + root.get("report").get("unchanged").asInt();
    }

    List<ItemView> items() {
      List<ItemView> out = new ArrayList<>();
      root.get("items").forEach(e -> out.add(ItemView.expected(e)));
      return out;
    }

    /** TS가 남긴 첫 거부 항목의 실패 종류(wire 값). */
    String rejectionKind() {
      return root.get("rejections").get(0).get("failureKind").asString();
    }

    /** TS가 남긴 첫 거부 항목의 공급자 위치. */
    JsonNode rejectionLocator() {
      return root.get("rejections").get(0).get("providerLocator");
    }

    JsonNode checkpoint() {
      return root.get("checkpoint").get("checkpoint");
    }
  }
}
