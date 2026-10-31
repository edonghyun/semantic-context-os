package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.support.ChildJvm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("db")
@Tag("process")
@DisplayName("다중 사용자 stdio는 실제 jar 기동을 거부한다(설계 §4.5)")
class MultiUserStdioRefusalTest {
  static final int STARTUP_REFUSED = 1;

  @Test
  @DisplayName("multi-user 환경의 mcp는 종료 1과 HTTP 안내로 끝난다")
  void refusesMultiUserStdio(@TempDir Path dir) throws Exception {
    var process = new StdioProcess(dir);

    Exit exit = process.run();

    // then
    assertThat(exit.code()).as(exit.output()).isEqualTo(STARTUP_REFUSED);
    assertThat(exit.output())
        .contains("scos.access.mode=multi-user에서는 stdio MCP를 열 수 없습니다. 데몬 HTTP MCP(사용자 토큰)를 쓰세요");
  }

  record Exit(int code, String output) {}

  static final class StdioProcess {
    private static final Path JAR =
        Path.of(System.getProperty("scos.boot.jar", "build/libs/scos-backend.jar"));
    private static final Duration PROCESS_LIMIT = Duration.ofSeconds(60);
    private final Path log;

    StdioProcess(Path dir) {
      PostgresSupport.truncateAll();
      log = dir.resolve("multi-user-stdio.log");
    }

    Exit run() throws Exception {
      Map<String, String> env =
          Map.of(
              "SCOS_DB_URL", PostgresSupport.jdbcUrl(),
              "SCOS_DB_USER", PostgresSupport.username(),
              "SCOS_DB_PASSWORD", PostgresSupport.password(),
              "GEMINI_API_KEY", "unused-in-test",
              "SCOS_GEMINI_BASE_URL", "http://127.0.0.1:9",
              "SCOS_RERANK_PROVIDER", "none",
              "SCOS_ACCESS_MODE", "multi-user");
      Process process = ChildJvm.startJar(JAR, log, env, "mcp");
      try {
        if (!process.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
          process.destroyForcibly().waitFor();
        return new Exit(process.exitValue(), Files.readString(log));
      } finally {
        if (process.isAlive()) process.destroyForcibly().waitFor();
      }
    }
  }
}
