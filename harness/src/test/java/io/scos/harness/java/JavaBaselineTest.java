package io.scos.harness.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import com.fasterxml.jackson.databind.JsonNode;
import io.scos.harness.HarnessRunner;
import io.scos.harness.fixture.FixtureSpec;
import io.scos.harness.mcp.JsonRpcStdioClient;
import io.scos.harness.server.JavaServerLauncher;
import io.scos.harness.testing.BaselineRun;
import io.scos.harness.testing.PostgresImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Java 구현(backend JAR) vs expected/ts. fixture p1을 적재(제품 import)해 mcp stdio로 43호출을 두 번(중간 재시작 포함)
 * 돌리는 실행을 @BeforeAll에서 한 번 하고, 계약별 테스트가 그 기록을 읽는다(TESTING §2.5 규칙 6). 호출별 pass / intentional /
 * known / fail을 보고한다. 통과한 호출은 전부 잠긴다: allowlist 밖 실패도, allowlist 안인데 통과(낡은 표시)도 실패다.
 */
@Tag("java")
@DisplayName("Java 구현은 fixture p1에서 TS 기준과 같은 MCP 응답을 낸다(ADR-0016)")
class JavaBaselineTest {
  private static final Path ROOT = Path.of(System.getProperty("harness.root", "."));
  private static final Path JAR =
      Path.of(System.getProperty("harness.java.jar", "../backend/build/libs/scos-backend.jar"));
  private static final Path ADAPTERS =
      Path.of(
          System.getProperty(
              "harness.java.adapters", "../backend/build/libs/scos-harness-adapters.jar"));
  private static final Path JAVA_HOME =
      Path.of(System.getProperty("harness.java.home", System.getProperty("java.home")));
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

  /** backend `PostgresSupport.IMAGE`와 같은 값(PostgresImageTest가 본다, TESTING §6). */
  static final DockerImageName IMAGE = PostgresImage.IMAGE;

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("fixture p1을 적재하고 43호출을 두 번(중간 재시작) 돌리면")
  class OnFixtureP1 {
    PostgreSQLContainer postgres;
    FixtureSpec fixture;
    BaselineRun run;
    HarnessRunner.Verification verification;

    @BeforeAll
    void runTwice() throws Exception {
      if (!Files.exists(JAR) || !Files.exists(ADAPTERS))
        throw new IllegalStateException(
            "given 준비 실패: 먼저 backend ./gradlew bootJar harnessJar: " + JAR + ", " + ADAPTERS);
      postgres = new PostgreSQLContainer(IMAGE);
      postgres.start();
      fixture = FixtureSpec.load(ROOT.resolve("fixtures/p1"));
      JavaServerLauncher launcher =
          new JavaServerLauncher(
              JAR,
              ADAPTERS,
              JAVA_HOME,
              new JavaServerLauncher.Database(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
      run = BaselineRun.twice(new HarnessRunner(fixture, launcher, CALL_TIMEOUT));
      verification = run.verify("ts");
      report(verification);
    }

    @AfterAll
    void stopDb() {
      if (postgres != null) postgres.stop();
    }

    @Test
    @DisplayName("카세트 미스가 없다")
    void noCassetteMisses() {
      // then
      assertThat(run.first().totalMisses()).isZero();
    }

    @Test
    @DisplayName("stdin EOF로 정상 종료한다")
    void stopsGracefully() {
      // then
      assertThat(run.first().shutdown().forced()).isFalse();
    }

    @Test
    @DisplayName("작업 디렉터리를 정리한다")
    void cleansWorkDirectory() {
      // then
      assertThat(run.first().workDirAfterCleanup()).doesNotExist();
    }

    @Test
    @DisplayName("중간 재시작 뒤에도 응답이 같다")
    void restartKeepsResponses() {
      // then
      assertThat(run.restartDifferences()).isEmpty();
    }

    @Test
    @DisplayName("assemble_context의 tokensUsed는 클램프된 예산을 넘지 않는다(P3 예산 초과 0)")
    void staysWithinBudget() throws IOException {
      // then
      assertThat(overBudgetCalls()).isEmpty();
    }

    @Test
    @DisplayName("allowlist 밖 불일치가 없다")
    void noUnexpectedDifferences() {
      // then
      assertThat(verification.failures()).isEmpty();
    }

    @Test
    @DisplayName("의도적 차이·알려진 실패로 표시했는데 같아진 호출이 없다(낡은 표시)")
    void noStaleMarks() {
      // then
      assertThat(verification.unexpectedlyEqual()).isEmpty();
    }

    /**
     * 호출마다 유효 예산을 손으로 적는다(tr-vocab-harness High 8 — 제품의 500..8000 클램프를 다시 구현하지 않는다): 기본 2000, 500은
     * 그대로, 10은 하한 500, 90000은 상한 8000.
     */
    static final Map<String, Integer> EFFECTIVE_BUDGETS =
        Map.of(
            "assemble-default-budget", 2000,
            "assemble-small-budget", 500,
            "assemble-budget-clamp-low", 500,
            "assemble-budget-clamp-high", 8000);

    /** 예산을 넘긴 assemble_context 호출(이름: 사용/예산). */
    List<String> overBudgetCalls() throws IOException {
      List<String> over = new ArrayList<>();
      for (FixtureSpec.Call c : fixture.calls()) {
        if (!c.tool().equals("assemble_context") || !c.args().has("query")) continue;
        JsonNode resp = run.response(c.name());
        if (resp.path("isError").asBoolean(false)) continue;
        Integer budget = EFFECTIVE_BUDGETS.get(c.name());
        if (budget == null)
          throw new IllegalStateException(
              "given 준비 실패: assemble_context 호출 "
                  + c.name()
                  + "의 유효 예산을 EFFECTIVE_BUDGETS에 손으로 적는다");
        int used =
            JsonRpcStdioClient.mapper()
                .readTree(resp.path("content").get(0).path("text").asText())
                .path("tokensUsed")
                .asInt();
        if (used > budget) over.add(c.name() + ": " + used + "/" + budget);
      }
      return over;
    }

    /** 하네스 보고(실행 기록 자체가 산출물 — TESTING §10 예외). */
    void report(HarnessRunner.Verification v) {
      System.out.println(
          "[java-harness] phase="
              + BaselineRun.phase()
              + " passed="
              + v.passed().size()
              + " intentional="
              + v.intentional().size()
              + " known="
              + v.known().size()
              + " failed="
              + v.failures().size()
              + " unexpectedlyEqual="
              + v.unexpectedlyEqual());
      for (String name : v.passed()) {
        var pol =
            fixture.calls().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .flatMap(FixtureSpec.Call::compare);
        System.out.println(
            "[java-harness] PASS "
                + name
                + pol.map(p -> " [policy " + p.policy() + ": " + p.reason() + "]").orElse(""));
      }
      v.intentional()
          .forEach((name, d) -> System.out.println("[java-harness] INTENTIONAL " + name + " " + d));
      v.known().forEach((name, d) -> System.out.println("[java-harness] KNOWN " + name + " " + d));
      v.failures()
          .forEach((name, d) -> System.out.println("[java-harness] FAIL " + name + " " + d));
    }
  }
}
