package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ChildJvm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 기동 시 임베딩 세대 검사의 실제 프로세스 계약(bootJar `java -jar`, stage2 §3.3): 공간에 저장된 세대가 설정(SCOS_EMBEDDER_*, 기본
 * gemini)과 다르거나 재임베딩이 끝나지 않았으면 mcp·daemon이 시작되지 않고 종료 코드 1로 끝나며 {@code scos reembed}를 안내한다 — 모델·차원을
 * 바꾼 뒤 검색이 조용히 0건이 되는 대신. 임베더는 부르지 않으므로 Gemini 주소는 닿지 않는 곳이다.
 */
@Tag("db")
@Tag("process")
@DisplayName("기동 시 임베딩 세대 검사는 실제 mcp·daemon 프로세스의 시작을 막는다(stage2 §3.3)")
class EmbeddingGenerationStartupTest {
  static final Path JAR =
      Path.of(System.getProperty("scos.boot.jar", "build/libs/scos-backend.jar"));

  static final Duration PROCESS_LIMIT = Duration.ofSeconds(60);
  static final int STARTUP_FAILED = 1;
  static final SpaceId LEGACY = new SpaceId("legacy");
  static final String CONFIGURED = "gemini:gemini-embedding-001:768:title+aliases+content";
  static final String OTHER = "openai-compatible:nomic-embed-text:v1.5:768:title+aliases+content";

  /** 실행 결과: 종료 코드와 합친 출력(stdout+stderr, daemon은 그 뒤에 serve.log). */
  record Exit(int code, String output) {}

  static void givenStoredGeneration(String generation) {
    PostgresSupport.truncateAll();
    PostgresTestAdapters.spaceMeta(PostgresSupport.dataSource())
        .set(LEGACY, "embedding:version", generation);
  }

  static Exit run(Path dir, String command) throws Exception {
    Path log = dir.resolve(command + ".log");
    Map<String, String> env = new HashMap<>();
    env.put("SCOS_DB_URL", PostgresSupport.jdbcUrl());
    env.put("SCOS_DB_USER", PostgresSupport.username());
    env.put("SCOS_DB_PASSWORD", PostgresSupport.password());
    env.put("GEMINI_API_KEY", "unused-in-test");
    env.put("SCOS_GEMINI_BASE_URL", "http://127.0.0.1:9");
    env.put("SCOS_RERANK_PROVIDER", "none");
    env.put("SCOS_DAEMON_PORT", "0");
    env.put("SCOS_MANAGEMENT_PORT", "0");
    Path logDir = dir.resolve("logs");
    env.put("SCOS_LOG_DIR", logDir.toString());
    Process p = ChildJvm.startJar(JAR, log, env, command);
    // 기동 검사를 지나 계속 돌면 상한에서 끊는다 — 종료 코드가 1이 아니어서 then이 실패한다
    if (!p.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS)) p.destroyForcibly().waitFor();
    Path serveLog = logDir.resolve("serve.log");
    String daemonLog = Files.exists(serveLog) ? Files.readString(serveLog) : "";
    return new Exit(p.exitValue(), Files.readString(log) + daemonLog);
  }

  @AfterEach
  void clearStoredGeneration() {
    PostgresSupport.truncateAll(); // 같은 JVM의 다른 프로세스 테스트가 이 세대를 보지 않게
  }

  @Test
  @DisplayName("mcp는 공간 세대가 설정과 다르면 종료 코드 1로 기동을 거부하고 reembed를 안내한다")
  void mcpRefusesOtherGeneration(@TempDir Path dir) throws Exception {
    // given
    givenStoredGeneration(OTHER);

    // when
    Exit exit = run(dir, "mcp");

    // then
    assertThat(exit.code()).as(exit.output()).isEqualTo(STARTUP_FAILED);
    assertThat(exit.output())
        .contains(
            "space legacy: stored embedding generation "
                + OTHER
                + ", configured "
                + CONFIGURED
                + ". Run 'scos reembed --space=legacy' or restore SCOS_EMBEDDER_* settings.");
  }

  @Test
  @DisplayName("daemon은 투영 공간 가운데 pending이 있으면 기동을 거부한다")
  void daemonRefusesPendingSpace(@TempDir Path dir) throws Exception {
    // given
    givenStoredGeneration("pending:" + CONFIGURED);

    // when
    Exit exit = run(dir, "daemon");

    // then
    assertThat(exit.code()).as(exit.output()).isEqualTo(STARTUP_FAILED);
    assertThat(exit.output())
        .contains(
            "space legacy: re-embedding to "
                + CONFIGURED
                + " did not finish. Re-run 'scos reembed --space=legacy'.");
  }
}
