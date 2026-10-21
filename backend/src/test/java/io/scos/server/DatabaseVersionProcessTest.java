package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.support.ChildJvm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * 기동 시 DB 버전 검사의 실제 프로세스 계약(bootJar `java -jar`): 기대 버전이 실제 DB와 다르면 명령이 시작되지 않고 종료 코드 1로 끝나며 원인을
 * stderr에 남긴다(ADR-0027). 기대값을 일부러 틀리게 줘서 운영 이미지가 바뀐 경우를 흉내 낸다.
 */
@Tag("db")
@Tag("process")
@DisplayName("기동 시 DB 버전 검사는 실제 명령 프로세스의 시작을 막는다(ADR-0027)")
class DatabaseVersionProcessTest {
  static final Path JAR =
      Path.of(System.getProperty("scos.boot.jar", "build/libs/scos-backend.jar"));

  static final Duration PROCESS_LIMIT = Duration.ofSeconds(60);
  static final int STARTUP_FAILED = 1;

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("기대와 다른 DB 버전으로 명령을 실행하면")
  class WrongExpectation {
    int exitCode;
    String stdout;
    String stderr;

    @BeforeAll
    void runWithWrongExpectation(@TempDir Path dir) throws Exception {
      Path out = dir.resolve("out");
      Path err = dir.resolve("err");
      Process p =
          ChildJvm.startJarSeparated(
              JAR,
              out,
              err,
              Map.of(
                  "SCOS_DB_URL", PostgresSupport.jdbcUrl(),
                  "SCOS_DB_USER", PostgresSupport.username(),
                  "SCOS_DB_PASSWORD", PostgresSupport.password(),
                  "GEMINI_API_KEY", "unused-in-test",
                  "SCOS_DB_EXPECTED_SERVER_VERSION", "16.4"),
              "source",
              "list");
      if (!p.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: 프로세스가 끝나지 않았다");
      exitCode = p.exitValue();
      stdout = Files.readString(out);
      stderr = Files.readString(err);
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsWithStartupFailure() {
      // then
      assertThat(exitCode).isEqualTo(STARTUP_FAILED);
    }

    @Test
    @DisplayName("기대와 실제 버전을 stderr에 남긴다")
    void reportsVersions() {
      // then
      assertThat(stderr).contains("database version mismatch: server 17.11 (expected 16.4)");
    }

    @Test
    @DisplayName("명령 결과를 쓰지 않는다")
    void writesNothing() {
      // then
      assertThat(stdout).isEmpty();
    }
  }
}
