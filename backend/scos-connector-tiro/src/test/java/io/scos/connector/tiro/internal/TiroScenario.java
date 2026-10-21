package io.scos.connector.tiro.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.RawContentCapture;
import io.scos.connector.tiro.TiroAdapter;
import io.scos.connector.tiro.TiroAssembly;
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
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tiro 어댑터 fixture 테스트(TESTING §3.6 3층)의 연결을 소유한다: 가짜 Tiro 서버(TS 데이터셋), 기다리지 않는 공급자 실행기(대기는 기록만),
 * 소스 설정, TS 기대 결과(expected-raw-items.json) 읽기. 본문은 "어떤 설정·체크포인트·응답으로 걸으면 무엇이 나오는가"만 쓴다.
 */
final class TiroScenario implements AutoCloseable {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
  static final String LIST = "/v1/external/workspaces/ws-synthetic/notes";
  static final String WRONG_TOKEN = "wrong-key";

  private final FakeTiroServer server = TiroAssembly.server();
  private final MutableClock clock = new MutableClock(NOW);
  private final List<Duration> waits = new ArrayList<>();
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
  private String token = server.token();

  /** 노트 경로(상세), 또는 그 아래 자원 경로. */
  static String note(String guid, String... below) {
    return "/v1/external/notes/" + guid + (below.length == 0 ? "" : "/" + String.join("/", below));
  }

  // ---- 장애·응답 ----

  void withWrongToken() {
    token = WRONG_TOKEN;
  }

  /** 경로의 다음 요청 n번이 이 장애로 답한다. */
  void failNext(String path, int times, FakeTiroServer.Fault fault) {
    server.failNext(path, times, fault);
  }

  /** 경로가 path이고 쿼리 커서가 cursor인 다음 요청 n번이 이 장애로 답한다. */
  void failOnCursor(String path, String cursor, int times, FakeTiroServer.Fault fault) {
    server.failOnCursor(path, cursor, times, fault);
  }

  /** 경로가 데이터셋 대신 이 본문으로 답한다. */
  void answer(String path, String body) {
    server.answer(path, body);
  }

  // ---- 수집 ----

  Collection collect() {
    return collect(Optional.empty(), server.configJson());
  }

  /** 이어 수집의 앞 단계: 처음부터 수집해 페이지를 냈다(실패하거나 페이지가 없으면 given 준비 실패). */
  Collection givenCollected() {
    Collection first = collect();
    if (first.failure.isPresent() || first.pages.isEmpty())
      throw new IllegalStateException(
          "given 준비 실패: 첫 수집이 페이지를 내야 한다 — " + first.failureMessage().orElse("페이지 0"));
    return first;
  }

  Collection collectFrom(Checkpoint checkpoint) {
    return collect(Optional.of(checkpoint), server.configJson());
  }

  Collection collectWithConfig(String configJson) {
    return collect(Optional.empty(), configJson);
  }

  private Collection collect(Optional<Checkpoint> checkpoint, String configJson) {
    Collection out = new Collection();
    try {
      adapter()
          .collect(
              new CollectionInput(
                  TiroAssembly.instance(configJson, NOW).descriptor(),
                  checkpoint,
                  CollectionMode.INCREMENTAL,
                  calls,
                  RawContentCapture.defaults(),
                  CancellationToken.none()),
              out.pages::add);
    } catch (CallFailureException e) {
      out.failure = Optional.of(e.failure());
    } catch (TiroProtocolException e) {
      out.failure = Optional.of(e.failure());
    }
    return out;
  }

  CollectionCoverage coverage(CollectionMode mode) {
    return adapter().coverage(new SourceConfig(1, server.configJson()), mode, Optional.empty());
  }

  /** 설정 검증 실패의 종류(통과하면 비어 있다). */
  Optional<CallFailure.Kind> validate(int schemaVersion, String configJson) {
    try {
      adapter().validateConfig(new SourceConfig(schemaVersion, configJson));
      return Optional.empty();
    } catch (CallFailureException e) {
      return Optional.of(e.failure().kind());
    }
  }

  // ---- 관찰 ----

  /** 공급자 호출 지표가 쌓이는 레지스트리(`assertThatMetrics`로 단언한다). */
  MeterRegistry metrics() {
    return metrics;
  }

  List<Duration> waits() {
    return List.copyOf(waits);
  }

  List<String> requests() {
    return server.requests();
  }

  List<String> authorizations() {
    return server.headers("Authorization");
  }

  List<String> accepts() {
    return server.headers("Accept");
  }

  String bearerToken() {
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

  private TiroAdapter adapter() {
    return TiroAssembly.adapter(server.baseUrl(), token, clock);
  }

  /** 한 번의 수집: 페이지들 또는 실패. */
  static final class Collection {
    private final List<CollectionPage> pages = new ArrayList<>();

    /** 공급자 호출 실패 또는 Tiro 형식 위반의 내부 분류(run errorKind와 별개). */
    private Optional<CallFailure> failure = Optional.empty();

    List<CollectionPage> pages() {
      return List.copyOf(pages);
    }

    List<ItemView> upserts() {
      return mutations().stream()
          .filter(RawMutation.Upsert.class::isInstance)
          .map(m -> ItemView.of(((RawMutation.Upsert) m).item()))
          .toList();
    }

    ItemView upsert(String externalId) {
      return upserts().stream()
          .filter(i -> i.externalId().equals(externalId))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("upsert 없음: " + externalId));
    }

    List<String> deletes() {
      return mutations().stream()
          .filter(RawMutation.Delete.class::isInstance)
          .map(RawMutation::externalId)
          .toList();
    }

    private List<RawMutation> mutations() {
      return pages.stream().flatMap(p -> p.mutations().stream()).toList();
    }

    List<RejectedItem> rejections() {
      return pages.stream().flatMap(p -> p.rejections().stream()).toList();
    }

    Checkpoint firstCheckpoint() {
      return pages.getFirst().nextCheckpoint();
    }

    Checkpoint finalCheckpoint() {
      return pages.getLast().nextCheckpoint();
    }

    Optional<CallFailure.Kind> failureKind() {
      return failure.map(CallFailure::kind);
    }

    Optional<String> failureMessage() {
      return failure.map(CallFailure::message);
    }
  }

  /** 항목 하나의 비교용 값(TS 기대 JSON과 같은 필드). */
  record ItemView(
      String externalId,
      String resourceType,
      String sourceVersion,
      String sourceCreatedAt,
      String sourceUpdatedAt,
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
          a.sourceCreatedAt().orElseThrow(),
          a.sourceUpdatedAt().orElseThrow(),
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
          e.get("sourceCreatedAt").asString(),
          e.get("sourceUpdatedAt").asString(),
          e.get("metadata"),
          e.get("content").get("mediaType").asString(),
          e.get("content").get("value"),
          Instant.parse(e.get("observedAt").asString()));
    }
  }

  /** TS TiroAdapter가 같은 데이터셋에서 낸 첫 수집 결과(expected-raw-items.json 최상위). */
  static final class TsCollection {
    private final JsonNode run;

    TsCollection() {
      try {
        run =
            JSON.readTree(
                Files.readString(TiroAssembly.FIXTURES.resolve("expected-raw-items.json")));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    int pagesApplied() {
      return run.get("report").get("pagesApplied").asInt();
    }

    int rejected() {
      return run.get("report").get("rejected").asInt();
    }

    List<ItemView> items() {
      List<ItemView> out = new ArrayList<>();
      run.get("items").forEach(e -> out.add(ItemView.expected(e)));
      return out;
    }

    /** 이 외부 id의 TS 원본. */
    ItemView item(String externalId) {
      return items().stream()
          .filter(i -> i.externalId().equals(externalId))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("TS 원본 없음: " + externalId));
    }

    /** TS가 보낸 경로 + 쿼리(가짜 서버 기록과 같이 디코드). */
    List<String> requests() {
      List<String> out = new ArrayList<>();
      run.get("requests")
          .forEach(r -> out.add(URLDecoder.decode(r.asString(), StandardCharsets.UTF_8)));
      return out;
    }

    JsonNode checkpoint() {
      return run.get("checkpoint").get("checkpoint");
    }

    /** allNotes 설정 소스(allNotesRun)의 수집 설정 값 JSON. */
    String allNotesConfig() {
      return JSON.writeValueAsString(run.get("allNotesRun").get("config").get("value"));
    }

    /** allNotes 수집에서 TS가 보낸 경로 + 쿼리(디코드). */
    List<String> allNotesRequests() {
      List<String> out = new ArrayList<>();
      run.get("allNotesRun")
          .get("requests")
          .forEach(r -> out.add(URLDecoder.decode(r.asString(), StandardCharsets.UTF_8)));
      return out;
    }

    JsonNode allNotesCheckpoint() {
      return run.get("allNotesRun").get("checkpoint").get("checkpoint");
    }
  }
}
