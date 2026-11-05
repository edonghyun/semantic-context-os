package io.scos;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SnapshotShrinkOverrides;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SourceSchedule;
import io.scos.collection.internal.StoredCheckpoint;
import io.scos.collection.internal.credentials.EnvironmentCredentials;
import io.scos.collection.internal.credentials.SchemeCredentialResolver;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.engine.support.text.Text;
import io.scos.server.ScosApplication;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.FileSystemUtils;

/**
 * 명령 스모크 테스트의 연결을 소유한다: 실제 Spring 조립(ScosApplication) + Testcontainers + 가짜 Slack. 임베더·자격 증명만 테스트
 * 빈으로 바꾼다(Gemini 키·환경변수 없이). 본문은 "어떤 명령을 실행하면 종료 코드와 DB가 어떻게 되는가"만 쓴다. 만들 때 DB를 비운다.
 */
@Tag("db")
final class CommandScenario implements AutoCloseable {
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"));
  static final SpaceId SPACE = new SpaceId("legacy");

  /** 테스트 임베더({@link TestDoubles#testEmbedder})의 임베딩 세대 — 명령이 설정 세대로 보는 값. */
  static final String GENERATION = "test:command-scenario:4:title+aliases+content";

  static final String PUBLIC_CHANNELS = "{\"conversationTypes\":[\"public_channel\"]}";
  private static final List<String> PROPERTIES =
      List.of(
          "SCOS_DB_URL",
          "SCOS_DB_USER",
          "SCOS_DB_PASSWORD",
          "GEMINI_API_KEY",
          "SCOS_SLACK_BASE_URL",
          "SCOS_LEASE_TTL");

  private final FakeSlackServer slack;
  private final Path configDir;
  private final PostgresCollectionConfiguration.Adapters adapters;

  CommandScenario() {
    PostgresSupport.truncateAll();
    try {
      configDir = Files.createTempDirectory("scos-source-config-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      slack = new FakeSlackServer(FIXTURES.resolve("slack-dataset.json"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    System.setProperty("scos.test.slack-token", slack.token());
    adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
  }

  /** 명령을 실행하고 종료 코드를 돌려준다. application.yml의 자리표시자는 시스템 프로퍼티로도 풀린다 — 환경변수 이름 규약 그대로. */
  int run(String... args) {
    return run(ctx -> {}, args);
  }

  /** 명령을 실행하는 동안 조립된 컨텍스트를 들여다본다. */
  int run(Consumer<ConfigurableApplicationContext> inspect, String... args) {
    System.setProperty("SCOS_DB_URL", PostgresSupport.jdbcUrl());
    System.setProperty("SCOS_DB_USER", PostgresSupport.username());
    System.setProperty("SCOS_DB_PASSWORD", PostgresSupport.password());
    System.setProperty("GEMINI_API_KEY", "unused-in-test");
    System.setProperty("SCOS_SLACK_BASE_URL", slack.baseUrl());
    System.setProperty("SCOS_LEASE_TTL", "30s");
    try (ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(ScosApplication.class, TestDoubles.class)
            .web(WebApplicationType.NONE)
            .run(args)) {
      inspect.accept(ctx);
      return SpringApplication.exit(ctx);
    } finally {
      PROPERTIES.forEach(System::clearProperty);
    }
  }

  /** 이 값을 담은 TS 형식 설정 파일(`{"schemaVersion":1,"value":…}`)을 만들고 경로를 돌려준다. */
  Path configFile(String valueJson) {
    try {
      Path file = Files.createTempFile(configDir, "source-", ".json");
      return Files.writeString(file, "{\"schemaVersion\":1,\"value\":" + valueJson + "}");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * `source add`로 Slack 소스를 등록한다. 설정 값은 설정 파일로 넘긴다. options는 덧붙일 선택 인자(`--interval-seconds=…` 등).
   */
  int addSource(String id, String configJson, String... options) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "source",
                "add",
                "--id=" + id,
                "--kind=slack",
                "--name=" + id,
                "--config=" + configFile(configJson),
                "--credential-ref=" + SlackAssembly.CREDENTIAL_REF));
    args.addAll(List.of(options));
    return run(args.toArray(String[]::new));
  }

  /** 등록 뒤 설정이 어긋난 소스(DM 유형인데 channelIds 없음) — 런타임의 validateConfig가 run 시작 전에 던진다. */
  void givenDriftedSource(String id) {
    Instant at = Instant.parse("2026-10-14T00:00:00Z");
    adapters
        .sources()
        .save(
            SourceInstances.of(
                new SourceInstanceId(id),
                SlackAssembly.KIND,
                "{\"conversationTypes\":[\"im\"]}",
                SlackAssembly.CREDENTIAL_REF,
                at));
  }

  /** 늘 증분으로만 수집하는(complete_snapshot이 없는) Jira 소스 — 명령이 어댑터의 coverage만 묻는다. */
  void givenIncrementalOnlySource(String id) {
    adapters
        .sources()
        .save(
            SourceInstances.of(
                new SourceInstanceId(id),
                "jira",
                "{}",
                "file:/run/secrets/jira-token",
                Instant.parse("2026-10-14T00:00:00Z")));
  }

  /** `import --file`이 읽는 유닛 JSON(유닛마다 벡터 하나, 문서도 하나씩)을 만들고 경로를 돌려준다. */
  Path unitsFile(double[]... embeddings) {
    StringBuilder units = new StringBuilder();
    for (int i = 0; i < embeddings.length; i++) {
      if (i > 0) units.append(',');
      units.append(
          "{\"id\":\"u%d\",\"provenance\":{\"documentId\":\"doc-%d\"},\"title\":\"제목 %d\",\"content\":\"본문 %d\",\"embedding\":%s}"
              .formatted(i, i, i, i, Arrays.toString(embeddings[i])));
    }
    try {
      Path file = Files.createTempFile(configDir, "units-", ".json");
      return Files.writeString(file, "{\"units\":[" + units + "]}");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 참조 밖 blob 하나(stored_at = 실제 현재 − age) — 명령은 시스템 시계로 유예를 잰다. */
  void givenOrphanBlob(String sha, Duration age) {
    JdbcClient.create(PostgresSupport.dataSource())
        .sql(
            "INSERT INTO raw_blobs (sha256, media_type, size, data, stored_at) VALUES (:sha, 'application/json', 10, :data, :at)")
        .param("sha", sha)
        .param("data", new byte[10])
        .param("at", Timestamp.from(Instant.now().minus(age)))
        .update();
  }

  /** 이 세대로 임베딩된(벡터 {@code vector}) 유닛 n개가 있는 공간 — 설정을 바꾸기 전에 투영해 둔 공간. */
  void givenEmbeddedSpace(int n, String generation, double[] vector) {
    List<ContextUnit> all = new ArrayList<>();
    for (int i = 0; i < n; i++)
      all.add(
          ContextUnits.unit("u" + i)
              .inDocument("doc-" + i)
              .titled("제목 " + i)
              .content("본문 " + i)
              .build());
    var dataSource = PostgresSupport.dataSource();
    PostgresTestAdapters.unitMaintenance(dataSource).upsert(SPACE, all);
    PostgresTestAdapters.vectorIndex(dataSource)
        .upsert(
            SPACE,
            all.stream()
                .map(u -> new IndexedUnit(u.id(), u.documentId(), u.partition(), false, vector))
                .toList());
    PostgresTestAdapters.spaceMeta(dataSource)
        .set(SPACE, EmbeddingGenerationCheck.META_KEY, generation);
  }

  // ---- 관찰 ----

  /** 공간에 저장된 임베딩 세대. */
  Optional<String> storedGeneration() {
    return PostgresTestAdapters.spaceMeta(PostgresSupport.dataSource())
        .get(SPACE, EmbeddingGenerationCheck.META_KEY);
  }

  /** 공간의 모든 유닛 벡터(적재 순서). */
  List<double[]> vectors() {
    var dataSource = PostgresSupport.dataSource();
    List<String> ids =
        PostgresTestAdapters.unitMaintenance(dataSource).listAll(SPACE).stream()
            .map(ContextUnit::id)
            .toList();
    Map<String, double[]> byId =
        PostgresTestAdapters.vectorIndex(dataSource)
            .vectorsOf(SPACE, AccessScope.singleUser(), ids);
    return ids.stream().map(byId::get).toList();
  }

  /** `source add` 뒤에 인자를 그대로 붙여 실행한다. */
  int sourceAdd(String... args) {
    List<String> all = new ArrayList<>(List.of("source", "add"));
    all.addAll(List.of(args));
    return run(all.toArray(String[]::new));
  }

  SourceConfig config(String source) {
    return adapters.sources().get(new SourceInstanceId(source)).orElseThrow().config();
  }

  SourceSchedule schedule(String source) {
    return adapters.sources().get(new SourceInstanceId(source)).orElseThrow().schedule();
  }

  /** 소스별 스냅샷 축소 보호 기준(ADR-0028). */
  SnapshotShrinkOverrides shrinkGuard(String source) {
    return adapters.sources().get(new SourceInstanceId(source)).orElseThrow().shrinkGuard();
  }

  /** 마지막 축소 허용 표시(ADR-0028). */
  Optional<SnapshotGuardStore.Acceptance> shrinkAcceptance(String source) {
    return adapters.guards().lastAcceptance(new SourceInstanceId(source));
  }

  /** 저장된 체크포인트(revision과 진행 위치). */
  StoredCheckpoint checkpoint(String source) {
    return adapters.checkpoints().read(new SourceInstanceId(source));
  }

  int enabledSources() {
    return adapters.sources().listEnabled().size();
  }

  long rawItems(String source) {
    return adapters.rawItems().count(new SourceInstanceId(source));
  }

  int pendingRequests() {
    return adapters.outbox().pending().size();
  }

  int units() {
    return PostgresTestAdapters.unitMaintenance(PostgresSupport.dataSource()).listAll(SPACE).size();
  }

  Optional<String> latestErrorKind(String source) {
    return adapters.runs().latest(new SourceInstanceId(source)).flatMap(r -> r.errorKind());
  }

  boolean hasRun(String source) {
    return adapters.runs().latest(new SourceInstanceId(source)).isPresent();
  }

  List<String> blobShas() {
    return JdbcClient.create(PostgresSupport.dataSource())
        .sql("SELECT sha256 FROM raw_blobs ORDER BY sha256")
        .query(String.class)
        .list();
  }

  @Override
  public void close() {
    try {
      slack.assertNoUnmatched();
    } finally {
      System.clearProperty("scos.test.slack-token");
      slack.close();
      FileSystemUtils.deleteRecursively(configDir.toFile());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class TestDoubles {
    /** 결정론 임베더(sha256 앞 2바이트, dim 4). */
    @Bean
    @Primary
    Embedder testEmbedder() {
      return new Embedder() {
        @Override
        public EmbedderIdentity identity() {
          return new EmbedderIdentity("test", "command-scenario", 4);
        }

        @Override
        public List<double[]> embed(List<String> texts) {
          List<double[]> out = new ArrayList<>();
          for (String t : texts) {
            String h = Text.sha256Hex(t);
            out.add(
                new double[] {
                  Integer.parseInt(h.substring(0, 2), 16),
                  Integer.parseInt(h.substring(2, 4), 16),
                  1,
                  1
                });
          }
          return out;
        }
      };
    }

    @Bean
    @Primary
    // 제품과 같은 스킴 해석, 환경변수만 시스템 속성으로(같은 JVM 안이라 환경변수를 바꿀 수 없다)
    CredentialResolver testCredentialResolver() {
      return ref ->
          SchemeCredentialResolver.of(
                  List.of(
                      new EnvironmentCredentials(
                          Map.of(
                              SlackAssembly.TOKEN_ENV,
                              System.getProperty("scos.test.slack-token")))))
              .resolve(ref);
    }
  }
}
