package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.connector.tiro.internal.FakeTiroServer;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.ChildJvm;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * 보호된 수집의 실제 프로세스 계약(bootJar `java -jar collect`, ADR-0028): 가짜 Tiro가 노트를 준 뒤 빈 목록을 200으로 주면 두 번째
 * `collect`는 노트를 지우지 않고, 실패가 아니므로 종료 코드 0이며, 보호 이벤트를 로그에 남긴다. 실행은 한 번, 계약은 테스트마다(TESTING §2.5 규칙
 * 6).
 */
@Tag("db")
@Tag("process")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("축소 보호 명령 프로세스 — 보호된 collect는 0, 잘못된 허용은 1(ADR-0028)")
class CollectProcessShrinkGuardTest {
  static final Path JAR =
      Path.of(System.getProperty("scos.boot.jar", "build/libs/scos-backend.jar"));
  static final Duration PROCESS_LIMIT = Duration.ofSeconds(60);
  static final Instant REGISTERED = Instant.parse("2026-10-16T00:00:00Z");
  static final String LIST = "/v1/external/workspaces/ws-synthetic/notes";
  static final String EMPTY_LISTING = "{\"content\":[],\"nextCursor\":null}";
  static final int SUCCESS = 0;
  static final int FAILURE = 1;
  static final SourceInstanceId INCREMENTAL_ONLY = new SourceInstanceId("jira-main");

  FakeTiroServer server;
  PostgresCollectionConfiguration.Adapters adapters;
  int notesBefore;
  int exitCode;
  String log;
  int rejectedExitCode;
  String rejectedLog;

  @BeforeAll
  void collectTwiceWithEmptySecondListing(@TempDir Path dir) throws Exception {
    PostgresSupport.truncateAll();
    adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
    server = TiroAssembly.server();
    adapters.sources().save(TiroAssembly.instance(server.configJson(), REGISTERED));
    if (collect(dir.resolve("first.log")) != SUCCESS)
      throw new IllegalStateException("given 준비 실패: 첫 collect가 실패했다");
    notesBefore = adapters.rawItems().count(TiroAssembly.SOURCE);
    if (notesBefore == 0) throw new IllegalStateException("given 준비 실패: 첫 collect가 노트를 적재하지 않았다");
    server.answer(LIST, EMPTY_LISTING);

    Path second = dir.resolve("second.log");
    exitCode = collect(second);
    log = ChildJvm.readLog(second);

    adapters
        .sources()
        .save(
            SourceInstances.of(
                INCREMENTAL_ONLY, "jira", "{}", "file:/run/secrets/jira-token", REGISTERED));
    Path rejected = dir.resolve("accept-shrink.log");
    rejectedExitCode = run(rejected, "source", "accept-shrink", INCREMENTAL_ONLY.value());
    rejectedLog = ChildJvm.readLog(rejected);
  }

  @AfterAll
  void close() {
    if (server != null) server.close();
  }

  int collect(Path logFile) throws Exception {
    return run(logFile, "collect", "--source=" + TiroAssembly.SOURCE.value());
  }

  int run(Path logFile, String... args) throws Exception {
    Process p =
        ChildJvm.startJar(
            JAR,
            logFile,
            Map.of(
                "SCOS_DB_URL", PostgresSupport.jdbcUrl(),
                "SCOS_DB_USER", PostgresSupport.username(),
                "SCOS_DB_PASSWORD", PostgresSupport.password(),
                "GEMINI_API_KEY", "unused-in-test",
                "SCOS_TIRO_BASE_URL", server.baseUrl(),
                "TIRO_SYNTHETIC_KEY", server.token()),
            args);
    if (!p.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("given 준비 실패: collect 프로세스가 끝나지 않았다 " + List.of(logFile));
    return p.exitValue();
  }

  @Test
  @DisplayName("보호는 실패가 아니라 종료 코드 0이다")
  void exitsZero() {
    // then
    assertThat(exitCode).isEqualTo(SUCCESS);
  }

  @Test
  @DisplayName("보호 이벤트를 로그에 남긴다")
  void logsGuardEvent() {
    // then
    assertThat(log).contains("collection.snapshot.reconcile_guarded");
  }

  @Test
  @DisplayName("첫 collect가 적재한 노트가 그대로 active다")
  void keepsNotes() {
    // when
    int notesAfter = adapters.rawItems().count(TiroAssembly.SOURCE);

    // then
    assertThat(notesAfter).isEqualTo(notesBefore);
  }

  @Nested
  @DisplayName("complete_snapshot이 없는 소스에 accept-shrink 프로세스를 실행하면")
  class AcceptIncrementalOnly {
    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // then
      assertThat(rejectedExitCode).isEqualTo(FAILURE);
    }

    @Test
    @DisplayName("적용되지 않는 소스라는 원인을 로그에 남긴다")
    void logsReason() {
      // then
      assertThat(rejectedLog)
          .contains(
              "accept-shrink applies only to sources that collect complete snapshots: jira-main");
    }
  }
}
