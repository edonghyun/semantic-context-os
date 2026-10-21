package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.slack.SlackAssembly;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.ChildJvm;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 명령의 실제 프로세스 계약(실제 bootJar `java -jar`): 잘못된 인자는 0이 아닌 종료 코드와 원인 메시지로 끝난다(TESTING §14-7). */
@Tag("db")
@Tag("process")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("명령 프로세스는 잘못된 인자·충돌을 종료 코드와 원인 메시지로 알린다(TESTING §14-7)")
class CommandProcessTest {
  static final Path JAR =
      Path.of(System.getProperty("scos.boot.jar", "build/libs/scos-backend.jar"));

  static final Duration PROCESS_LIMIT = Duration.ofSeconds(60);

  /** 끝난 명령 프로세스의 종료 코드와 로그. */
  record Finished(int exitCode, String log) {}

  static final SourceInstanceId SOURCE = SlackAssembly.SOURCE;
  static final String UNKNOWN_SOURCE = "missing-source";

  /** 다른 worker가 쥔 수집 점유(명령 프로세스보다 오래 간다). */
  static final Duration OTHER_CLAIM = Duration.ofHours(1);

  /** 기본 유예(30일) 밖·안의 나이. 명령은 시스템 시계로 유예를 잰다. */
  static final Duration BEYOND_GRACE = Duration.ofDays(31);

  static final Duration WITHIN_GRACE = Duration.ofDays(29);

  Finished invalidGraceDays;
  Finished dryRun;
  List<String> blobsAfterDryRun;
  long changesAfterDryRun;
  Finished collectWhileRunning;
  Finished cycleWhileRunning;
  Finished disable;
  Finished enableUnknown;
  PostgresCollectionConfiguration.Adapters adapters;

  /** 명령마다 실제 jar로 한 번씩 실행해 결과만 기록한다. */
  @BeforeAll
  void runCommands(@TempDir Path dir) throws Exception {
    PostgresSupport.truncateAll();
    invalidGraceDays =
        run(dir.resolve("invalid-grace-days.log"), "maintenance", "--grace-days=abc");
    adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
    givenOrphanBlob("old-orphan", BEYOND_GRACE);
    givenOrphanBlob("young-orphan", WITHIN_GRACE);
    givenExpiredChange("item-1");
    givenExpiredChange("item-2");
    dryRun = run(dir.resolve("dry-run.log"), "maintenance", "--dry-run");
    blobsAfterDryRun =
        jdbc().sql("SELECT sha256 FROM raw_blobs ORDER BY sha256").query(String.class).list();
    changesAfterDryRun = jdbc().sql("SELECT count(*) FROM raw_changes").query(Long.class).single();
    adapters
        .sources()
        .save(SlackAssembly.instance(SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP, Instant.now()));
    adapters.claims().tryClaim(SOURCE, new OwnerId("other-worker"), OTHER_CLAIM, Instant.now());
    collectWhileRunning =
        run(dir.resolve("collect-running.log"), "collect", "--source=" + SOURCE.value());
    cycleWhileRunning = run(dir.resolve("cycle-running.log"), "cycle");
    disable = run(dir.resolve("disable.log"), "source", "disable", SOURCE.value());
    enableUnknown = run(dir.resolve("enable-unknown.log"), "source", "enable", UNKNOWN_SOURCE);
  }

  static JdbcClient jdbc() {
    return JdbcClient.create(PostgresSupport.dataSource());
  }

  /** 참조 밖 blob 하나(10바이트, stored_at = 실제 현재 − age). */
  static void givenOrphanBlob(String sha, Duration age) {
    jdbc()
        .sql(
            "INSERT INTO raw_blobs (sha256, media_type, size, data, stored_at) VALUES (:sha, 'application/json', 10, :data, :at)")
        .param("sha", sha)
        .param("data", new byte[10])
        .param("at", Timestamp.from(Instant.now().minus(age)))
        .update();
  }

  /** 유예 밖(31일 전)의 변경 이력 한 건. */
  void givenExpiredChange(String externalId) {
    adapters
        .changes()
        .appendAll(
            List.of(
                new SourceChangeLog.Entry(
                    SOURCE,
                    Optional.of(externalId),
                    SourceChangeLog.Kind.UPSERT,
                    Optional.of("v1"),
                    Optional.empty(),
                    Instant.now().minus(BEYOND_GRACE))));
  }

  Finished run(Path log, String... args) throws Exception {
    Process p = ChildJvm.startJar(JAR, log, env(), args);
    if (!p.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("given 준비 실패: 프로세스가 끝나지 않았다 " + List.of(args));
    return new Finished(p.exitValue(), ChildJvm.readLog(log));
  }

  Map<String, String> env() {
    return Map.of(
        "SCOS_DB_URL", PostgresSupport.jdbcUrl(),
        "SCOS_DB_USER", PostgresSupport.username(),
        "SCOS_DB_PASSWORD", PostgresSupport.password(),
        "GEMINI_API_KEY", "unused-in-test");
  }

  @Nested
  @DisplayName("maintenance --grace-days=abc 프로세스는")
  class InvalidGraceDays {
    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsNonZero() {
      // then
      assertThat(invalidGraceDays.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("입력을 밝히는 거부 메시지를 로그에 남긴다")
    void logsRejection() {
      // then
      assertThat(invalidGraceDays.log())
          .contains("--grace-days must be a non-negative integer: abc");
    }
  }

  @Nested
  @DisplayName("유예 밖 고아 blob·변경 이력이 있는 DB의 maintenance --dry-run 프로세스는")
  class DryRun {
    @Test
    @DisplayName("종료 코드 0으로 끝난다")
    void exitsZero() {
      // then
      assertThat(dryRun.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("지울 blob 수와 크기를 DRY_RUN 보고 줄에 남긴다")
    void logsReport() {
      // then
      assertThat(dryRun.log())
          .contains(
              "maintenance: graceDays=30 mode=DRY_RUN blobs scanned=2 deleted=1 retainedByReference=0 retainedByGrace=1 bytesFreed=10 prunedChanges=0");
    }

    @Test
    @DisplayName("유예 밖 고아 blob을 지우지 않는다")
    void keepsBlobs() {
      // then
      assertThat(blobsAfterDryRun).containsExactly("old-orphan", "young-orphan");
    }

    @Test
    @DisplayName("유예 밖 변경 이력을 지우지 않는다")
    void keepsChanges() {
      // then
      assertThat(changesAfterDryRun).isEqualTo(2L);
    }
  }

  @Nested
  @DisplayName("다른 worker가 수집 중인 소스의 collect 프로세스는")
  class CollectWhileRunning {
    @Test
    @DisplayName("TS처럼 종료 코드 1로 끝난다")
    void exitsNonZero() {
      // then
      assertThat(collectWhileRunning.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 수집 중이라는 메시지를 로그에 남긴다")
    void logsAlreadyRunning() {
      // then
      assertThat(collectWhileRunning.log())
          .contains("Source collection is already running: " + SOURCE.value());
    }
  }

  @Nested
  @DisplayName("다른 worker가 수집 중인 소스가 있는 cycle 프로세스는")
  class CycleWhileRunning {
    @Test
    @DisplayName("그 소스를 실패로 세지 않고 종료 코드 0으로 끝난다")
    void exitsZero() {
      // then
      assertThat(cycleWhileRunning.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("그 소스를 건너뛰었다고 로그에 남긴다")
    void logsSkipped() {
      // then
      assertThat(cycleWhileRunning.log())
          .contains("collect skipped (already running): " + SOURCE.value());
    }
  }

  @Nested
  @DisplayName("source disable 프로세스는")
  class Disable {
    @Test
    @DisplayName("종료 코드 0으로 끝난다")
    void exitsZero() {
      // then
      assertThat(disable.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("소스를 enabled 목록에서 뺀다")
    void leavesEnabledList() {
      // then
      assertThat(adapters.sources().listEnabled()).isEmpty();
    }
  }

  @Nested
  @DisplayName("등록되지 않은 소스의 source enable 프로세스는")
  class EnableUnknown {
    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsNonZero() {
      // then
      assertThat(enableUnknown.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("소스가 없다는 메시지를 로그에 남긴다")
    void logsNotFound() {
      // then
      assertThat(enableUnknown.log()).contains("Source instance not found: " + UNKNOWN_SOURCE);
    }
  }
}
