package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.support.ChildJvm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * 조회 명령의 실제 프로세스 계약(bootJar `java -jar`, 제품 어댑터 조립): 결과는 stdout에만, 로그는 stderr에만 간다 — 운영자가 출력을 파이프로
 * 읽는다. 상태는 TS 조회 각본({@link QueryScenario}).
 */
@Tag("db")
@Tag("process")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("조회 명령 프로세스는 결과를 stdout에만, 로그를 stderr에만 쓴다")
class QueryCommandProcessTest {
  static final Path JAR =
      Path.of(System.getProperty("scos.boot.jar", "build/libs/scos-backend.jar"));

  static final Duration PROCESS_LIMIT = Duration.ofSeconds(60);
  static final int FAILED = 1;
  static final JsonNode FIXTURE = QueryScenario.fixture();

  /** 끝난 명령 프로세스의 종료 코드와 두 출력. */
  record Finished(int exitCode, String stdout, String stderr) {}

  Finished sourceList;
  Finished badCursor;

  @BeforeAll
  void runCommands(@TempDir Path dir) throws Exception {
    QueryScenario.replay(FIXTURE.get("scenario"));
    sourceList = run(dir, "source-list", "source", "list");
    badCursor = run(dir, "bad-cursor", "raw", "list", "--cursor=@@@");
  }

  Finished run(Path dir, String name, String... args) throws Exception {
    Path out = dir.resolve(name + ".out");
    Path err = dir.resolve(name + ".err");
    Process p = ChildJvm.startJarSeparated(JAR, out, err, env(), args);
    if (!p.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("given 준비 실패: 프로세스가 끝나지 않았다 " + List.of(args));
    return new Finished(p.exitValue(), Files.readString(out), Files.readString(err));
  }

  Map<String, String> env() {
    return Map.of(
        "SCOS_DB_URL", PostgresSupport.jdbcUrl(),
        "SCOS_DB_USER", PostgresSupport.username(),
        "SCOS_DB_PASSWORD", PostgresSupport.password(),
        "GEMINI_API_KEY", "unused-in-test");
  }

  /** TS 기대 출력 중 이 명령의 것(writeOutput 한 번 = console.log 한 줄). */
  static String tsOutput(String command) {
    for (JsonNode c : FIXTURE.get("cases")) {
      StringBuilder args = new StringBuilder();
      c.get("args").forEach(a -> args.append(args.isEmpty() ? "" : " ").append(a.asString()));
      if (args.toString().equals(command)) return c.get("output").get(0).asString() + "\n";
    }
    throw new IllegalArgumentException("기대 파일에 없는 명령: " + command);
  }

  @Nested
  @DisplayName("source list 프로세스는")
  class SourceList {
    @Test
    @DisplayName("stdout에 TS와 같은 목록만 쓴다")
    void stdoutIsTsOutput() {
      // then
      assertThat(sourceList.stdout()).isEqualTo(tsOutput("source list"));
    }

    @Test
    @DisplayName("종료 코드 0으로 끝난다")
    void exitsZero() {
      // then
      assertThat(sourceList.exitCode()).isZero();
    }
  }

  @Nested
  @DisplayName("읽을 수 없는 커서의 raw list 프로세스는")
  class BadCursor {
    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsNonZero() {
      // then
      assertThat(badCursor.exitCode()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("원인을 stderr에 남긴다")
    void reportsOnStderr() {
      // then
      assertThat(badCursor.stderr()).contains("Invalid raw catalog cursor: @@@");
    }

    @Test
    @DisplayName("stdout에는 아무것도 쓰지 않는다")
    void stdoutEmpty() {
      // then
      assertThat(badCursor.stdout()).isEmpty();
    }
  }
}
