package io.scos.server;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceSchedule;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.notion.NotionAssembly;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TS 조회 CLI 각본(harness/fixtures/ops/expected-cli.json의 scenario — TS 생성기 cliCases와 같은 순서)을
 * PostgreSQL에 재현하고, 조회 명령을 실행해 출력 줄을 돌려준다. 시각은 모두 각본의 now(조회 명령의 시계도), run id는 run-1부터, 어댑터는 각본대로
 * 페이지를 내는 스크립트다. 조회 명령의 어댑터 레지스트리는 제품 Slack·Jira·Tiro·Notion·Drive 어댑터(공급자를 부르지 않는다 — 설정만 읽는다). 각본
 * 단계가 기대와 다르게 끝나면 "given 준비 실패"로 멈춘다 — 바이트 비교 전체가 엉뚱한 이유로 실패하지 않게.
 */
@Tag("db")
final class QueryScenario {
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.ops.dir", "../harness/fixtures/ops"));
  static final ObjectMapper JSON = new ObjectMapper();
  private static final String UNUSED_BASE_URL = "http://127.0.0.1:9";

  private final JsonNode scenario;
  private final Instant now;
  private final MutableClock clock;
  private final DataSource dataSource = PostgresSupport.dataSource();
  private final AtomicInteger runs = new AtomicInteger();

  private QueryScenario(JsonNode scenario) {
    this.scenario = scenario;
    this.now = Instant.parse(scenario.get("now").asString());
    this.clock = new MutableClock(now);
  }

  /** 기대 파일 전체(version·scenario·cases). */
  static JsonNode fixture() {
    try {
      return JSON.readTree(Files.readString(FIXTURES.resolve("expected-cli.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** DB를 비우고 각본 상태를 만든다. */
  static QueryScenario replay(JsonNode scenario) {
    PostgresSupport.truncateAll();
    QueryScenario s = new QueryScenario(scenario);
    s.registerSources();
    s.collectJira();
    s.failJira();
    s.snapshotSlack();
    s.holdTiroLease();
    return s;
  }

  /**
   * TS 인자(`--limit 1`)를 Java 형식(`--limit=1`)으로 바꿔 조회 명령을 실행하고, 출력(writeOutput 한 번 = 한 덩어리)을 차례로
   * 돌려준다.
   */
  List<String> run(List<String> tsArgs) {
    List<String> outputs = new ArrayList<>();
    QueryCommands commands =
        new QueryCommands(
            PostgresCollectionConfiguration.adapters(dataSource).queries(),
            SourceAdapters.of(
                List.of(
                    SlackAssembly.adapter(UNUSED_BASE_URL, "unused", clock),
                    JiraAssembly.adapter(UNUSED_BASE_URL, "unused", clock),
                    TiroAssembly.adapter(UNUSED_BASE_URL, "unused", clock),
                    NotionAssembly.adapter(UNUSED_BASE_URL, "unused", clock),
                    GoogleDriveAssembly.adapter(UNUSED_BASE_URL, "unused", clock))),
            new FreshnessReader(PostgresTestAdapters.unitStore(dataSource), clock),
            Set.of(SpaceId.LEGACY),
            clock,
            new SourceQueryJson(JSON),
            outputs::add);
    var args = new DefaultApplicationArguments(javaArgs(tsArgs));
    switch (tsArgs.get(0)) {
      case "source" -> commands.source(tsArgs.get(1), args);
      case "raw" -> commands.raw(tsArgs.get(1), args);
      case "status" -> commands.overview(args);
      default -> throw new IllegalArgumentException("각본에 없는 명령: " + tsArgs);
    }
    return outputs;
  }

  private static String[] javaArgs(List<String> tsArgs) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < tsArgs.size(); i++) {
      String arg = tsArgs.get(i);
      if (arg.startsWith("--")) out.add(arg + "=" + tsArgs.get(++i));
      else out.add(arg);
    }
    return out.toArray(String[]::new);
  }

  // ---- 각본 단계(TS cliCases 순서) ----

  private void registerSources() {
    var store = PostgresCollectionConfiguration.adapters(dataSource).sources();
    for (JsonNode s : scenario.get("sources"))
      store.save(
          new SourceInstance(
              new SourceInstanceId(s.get("id").asString()),
              s.get("kind").asString(),
              s.get("displayName").asString(),
              new SourceConfig(
                  s.get("config").get("schemaVersion").asInt(),
                  JsJson.stringify(s.get("config").get("value"))),
              s.get("credentialRef").asString(),
              s.get("enabled").asBoolean(),
              new SourceSchedule(Optional.empty(), Optional.empty()),
              now,
              now));
  }

  /** run-1: 1쪽 upsert issue1·issue2 + 거부 1, 2쪽 delete issue2. */
  private void collectJira() {
    collect(
        "jira-main",
        CollectionMode.INCREMENTAL,
        RunStatus.SUCCEEDED_WITH_REJECTIONS,
        scripted(
            "jira",
            CollectionCoverage.INCREMENTAL,
            sink -> {
              sink.accept(
                  new CollectionPage(
                      List.of(
                          new RawMutation.Upsert(item(scenario.get("issue1"))),
                          new RawMutation.Upsert(item(scenario.get("issue2")))),
                      List.of(rejection(scenario.get("rejection"))),
                      new Checkpoint(1, "{\"page\":1}")));
              sink.accept(
                  new CollectionPage(
                      List.of(
                          new RawMutation.Delete(
                              externalId(scenario.get("issue2")),
                              Optional.empty(),
                              Optional.empty(),
                              Optional.empty(),
                              now)),
                      List.of(),
                      new Checkpoint(1, "{\"page\":2}")));
            }));
  }

  /** run-2: 공급자 호출 실패 → failed. */
  private void failJira() {
    JsonNode failure = scenario.get("failure");
    collect(
        "jira-main",
        CollectionMode.INCREMENTAL,
        RunStatus.FAILED,
        scripted(
            "jira",
            CollectionCoverage.INCREMENTAL,
            sink -> {
              throw new CallFailureException(
                  failure.get("operation").asString(),
                  CallFailure.of(
                      CallFailure.Kind.valueOf(
                          failure.get("kind").asString().toUpperCase(Locale.ROOT)),
                      failure.get("message").asString()));
            }));
  }

  /** run-3: Slack snapshot, 완전 스냅샷 판정, 메시지 1. */
  private void snapshotSlack() {
    collect(
        "slack-main",
        CollectionMode.SNAPSHOT,
        RunStatus.SUCCEEDED,
        scripted(
            "slack",
            CollectionCoverage.COMPLETE_SNAPSHOT,
            sink ->
                sink.accept(
                    CollectionPage.ofItems(
                        List.of(item(scenario.get("message"))),
                        new Checkpoint(2, "{\"done\":true}")))));
  }

  /** 다른 소유자가 tiro-main을 오래 점유한다. */
  private void holdTiroLease() {
    JsonNode lease = scenario.get("lease");
    if (PostgresCollectionConfiguration.adapters(dataSource)
        .claims()
        .tryClaim(
            new SourceInstanceId(lease.get("sourceInstanceId").asString()),
            new OwnerId(lease.get("ownerId").asString()),
            Duration.ofMillis(lease.get("ttlMs").asLong()),
            now)
        .isEmpty()) throw new IllegalStateException("given 준비 실패: tiro-main을 점유하지 못했다");
  }

  private void collect(
      String source, CollectionMode mode, RunStatus expected, SourceAdapter adapter) {
    CollectionRuntime.Report report =
        PostgresCollectionRuntime.over(dataSource, clock)
            .adapter(adapter)
            .owner("fixture")
            .runIds(() -> new RunId("run-" + runs.incrementAndGet()))
            .build()
            .collect(new SourceInstanceId(source), mode, new CancellationToken());
    if (report.status() != expected)
      throw new IllegalStateException(
          "given 준비 실패: " + report.runId().value() + "이 " + expected + "가 아니다: " + report.status());
  }

  /** 각본 페이지를 내는 어댑터(coverage 고정, 설정 검사 없음 — TS scripted). */
  private static SourceAdapter scripted(
      String kind, CollectionCoverage coverage, Consumer<PageSink> pages) {
    return new SourceAdapter() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public void validateConfig(SourceConfig config) {}

      @Override
      public CollectionCoverage coverage(
          SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
        return coverage;
      }

      @Override
      public void collect(CollectionInput input, PageSink sink) {
        pages.accept(sink);
      }
    };
  }

  // ---- 각본 JSON → 도메인 ----

  private static String externalId(JsonNode item) {
    return item.get("key").get("externalId").asString();
  }

  private static RawItem item(JsonNode item) {
    JsonNode content = item.get("content");
    return new RawItem(
        externalId(item),
        item.get("resourceType").asString(),
        item.get("sourceVersion").asString(),
        item.has("sourceOrder")
            ? Optional.of(
                new SourceOrder(
                    item.get("sourceOrder").get("scheme").asString(),
                    item.get("sourceOrder").get("position").asString()))
            : Optional.empty(),
        optionalText(item, "sourceCreatedAt"),
        optionalText(item, "sourceUpdatedAt"),
        Instant.parse(item.get("observedAt").asString()),
        JsJson.stringify(item.get("metadata")),
        Optional.of(
            new RawContent.Inline(
                content.get("mediaType").asString(), JsJson.stringify(content.get("value")))));
  }

  private static RejectedItem rejection(JsonNode r) {
    return new RejectedItem(
        RejectedItem.FailureKind.fromWire(r.get("failureKind").asString()),
        r.get("message").asString(),
        Optional.of(JsJson.stringify(r.get("providerLocator"))),
        Instant.parse(r.get("observedAt").asString()),
        Optional.empty());
  }

  private static Optional<String> optionalText(JsonNode node, String field) {
    return node.has(field) ? Optional.of(node.get(field).asString()) : Optional.empty();
  }
}
