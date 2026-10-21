package io.scos.harness.o2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.scos.harness.o2.ParallelComparison.Options;
import io.scos.harness.o2.ParallelComparison.Report;
import io.scos.harness.o2.ParallelComparison.Side;
import io.scos.harness.testing.FakeServerLauncher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 실제 stdio 프로세스 두 개(가짜 MCP 서버)로 끝까지 돌린다. 가짜 서버는 질의와 무관하게 정해진 hits를 돌려주고, 호출을 기록하고, 정한 차례에서
 * 죽거나·멈추거나·JSON이 아닌 줄·잘린 JSON을 낸다. 비싼 실행은 상황마다 한 번이다(TESTING §2.5 규칙 6) — 테스트는 기록만 읽는다.
 */
@DisplayName("병렬 비교는 서버 장애를 그 질의의 오류로 가두고 계속 비교한다(O2)")
class ParallelComparisonProcessTest {
  static final Duration TIMEOUT = Duration.ofSeconds(3);
  static final int LIMIT = 5;
  static final double EPSILON = 1e-12;
  static final int MAX_CONSECUTIVE_FAILURES = 3;
  static final List<ComparisonQuery> QUERIES =
      List.of(
          new ComparisonQuery("합성 질의 하나", Optional.empty()),
          new ComparisonQuery("합성 질의 둘", Optional.empty()),
          new ComparisonQuery("합성 질의 셋", Optional.empty()),
          new ComparisonQuery("합성 질의 넷", Optional.empty()),
          new ComparisonQuery("합성 질의 다섯", Optional.empty()));

  static String searchResult(String... documentIds) throws IOException {
    StringBuilder hits = new StringBuilder("[");
    for (int i = 0; i < documentIds.length; i++)
      hits.append(i == 0 ? "" : ",")
          .append("{\"documentId\":\"")
          .append(documentIds[i])
          .append("\",\"score\":1}");
    hits.append("]");
    String text = ComparisonQuery.JSON.writeValueAsString("{\"hits\":" + hits + "}");
    return "{\"content\":[{\"type\":\"text\",\"text\":" + text + "}]}";
  }

  /** 상황 하나: 임시 디렉터리, 두 쪽 환경, 한 번의 실행과 그 기록. */
  abstract static class Situation {
    Path dir;
    Report report;

    abstract Map<String, String> a() throws IOException;

    abstract Map<String, String> b() throws IOException;

    int warmup() {
      return 0;
    }

    int maxConsecutiveFailures() {
      return MAX_CONSECUTIVE_FAILURES;
    }

    @BeforeAll
    void run() throws IOException {
      dir = Files.createTempDirectory("o2-process-");
      report =
          new ParallelComparison(
                  new Options(
                      LIMIT,
                      LIMIT,
                      RankingMetrics.DEFAULT_RBO_P,
                      Thresholds.NONE,
                      TIMEOUT,
                      warmup(),
                      maxConsecutiveFailures(),
                      false))
              .run(QUERIES, side("a", a()), side("b", b()));
    }

    Side side(String name, Map<String, String> env) throws IOException {
      Path cwd = Files.createDirectories(dir.resolve(name));
      return new Side(name, FakeServerLauncher.launch(cwd, env));
    }

    Map<String, String> answering(String name, String... ids) throws IOException {
      Map<String, String> env = new HashMap<>();
      env.put("FAKE_RESPONSE", searchResult(ids));
      env.put("FAKE_NAME", name);
      env.put("FAKE_CALL_LOG", dir.resolve("calls.log").toString());
      return env;
    }

    @AfterAll
    void cleanup() throws IOException {
      try (var walk = Files.walk(dir)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
      }
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("두 서버가 반쯤 겹치게 답하면(워밍업 1)")
  class HalfOverlap extends Situation {
    @Override
    Map<String, String> a() throws IOException {
      return answering("a", "d1", "d2");
    }

    @Override
    Map<String, String> b() throws IOException {
      return answering("b", "d1", "d3");
    }

    @Override
    int warmup() {
      return 1;
    }

    List<String> calls() throws IOException {
      return Files.readAllLines(dir.resolve("calls.log"));
    }

    @Test
    @DisplayName("질의마다 한 줄을 남긴다")
    void oneRowPerQuery() {
      // then
      assertThat(report.rows()).hasSize(QUERIES.size());
    }

    @Test
    @DisplayName("응답 문서로 Jaccard를 낸다")
    void jaccardFromResponses() {
      // then
      assertThat(report.summary().jaccard().orElseThrow().mean())
          .isCloseTo(1.0 / 3, within(EPSILON));
    }

    @Test
    @DisplayName("측정 전에 쪽마다 첫 질의를 한 번씩 더 부른다(워밍업)")
    void warmsUp() throws IOException {
      // then
      assertThat(calls().subList(0, 2)).containsExactly("a\t합성 질의 하나", "b\t합성 질의 하나");
    }

    @Test
    @DisplayName("질의마다 먼저 부르는 쪽을 번갈아 바꾼다")
    void alternatesOrder() throws IOException {
      // then
      assertThat(calls().subList(2, 6))
          .containsExactly("a\t합성 질의 하나", "b\t합성 질의 하나", "b\t합성 질의 둘", "a\t합성 질의 둘");
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("B가 셋째 호출에서 죽으면")
  class Dies extends Situation {
    @Override
    Map<String, String> a() throws IOException {
      return answering("a", "d1");
    }

    @Override
    Map<String, String> b() throws IOException {
      Map<String, String> env = answering("b", "d1");
      env.put("FAKE_DIE_AFTER", "3");
      return env;
    }

    @Test
    @DisplayName("그 질의만 오류다")
    void onlyThatQueryFails() {
      // then
      assertThat(report.summary().errors()).isEqualTo(1);
    }

    @Test
    @DisplayName("다시 띄워 나머지 질의를 비교한다")
    void restartsAndContinues() {
      // then
      assertThat(report.summary().restarts()).containsEntry("b", 1);
    }

    @Test
    @DisplayName("오류 메시지에는 예외 종류만 싣는다")
    void errorMessageIsTypeOnly() {
      // then
      assertThat(report.rows().get(2).b().hits().error().orElseThrow())
          .matches("call failed: [A-Za-z]+");
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("B가 셋째 호출에서 응답하지 않으면(타임아웃)")
  class Hangs extends Situation {
    @Override
    Map<String, String> a() throws IOException {
      return answering("a", "d1");
    }

    @Override
    Map<String, String> b() throws IOException {
      Map<String, String> env = answering("b", "d1");
      env.put("FAKE_HANG_AFTER", "3");
      return env;
    }

    @Test
    @DisplayName("그 질의만 오류다")
    void onlyThatQueryFails() {
      // then
      assertThat(report.summary().errors()).isEqualTo(1);
    }

    @Test
    @DisplayName("다음 질의는 비교된다")
    void nextQueryCompared() {
      // then
      assertThat(report.rows().get(3).compared()).isTrue();
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("B가 셋째 호출에서 죽고 워밍업이 1이면")
  class DiesWithWarmup extends Situation {
    @Override
    Map<String, String> a() throws IOException {
      return answering("a", "d1");
    }

    @Override
    Map<String, String> b() throws IOException {
      Map<String, String> env = answering("b", "d1");
      env.put("FAKE_DIE_AFTER", "3");
      return env;
    }

    @Override
    int warmup() {
      return 1;
    }

    @Test
    @DisplayName("다시 띄운 뒤 첫 질의로 다시 워밍업한다")
    void warmsUpAfterRestart() throws IOException {
      List<String> calls = Files.readAllLines(dir.resolve("calls.log"));

      // then: 워밍업 · 첫 질의 · 둘째 질의(죽음, 다시 띄움) · 다시 워밍업
      assertThat(calls)
          .filteredOn(line -> line.startsWith("b\t"))
          .startsWith("b\t합성 질의 하나", "b\t합성 질의 하나", "b\t합성 질의 둘", "b\t합성 질의 하나");
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("B가 매번 첫 호출에서 죽고 연속 실패 상한이 2면")
  class KeepsDying extends Situation {
    @Override
    Map<String, String> a() throws IOException {
      return answering("a", "d1");
    }

    @Override
    Map<String, String> b() throws IOException {
      Map<String, String> env = answering("b", "d1");
      env.put("FAKE_DIE_AFTER", "1");
      return env;
    }

    @Override
    int maxConsecutiveFailures() {
      return 2;
    }

    @Test
    @DisplayName("상한에 닿으면 더 띄우지 않는다")
    void stopsRestarting() {
      // then
      assertThat(report.summary().restarts()).containsEntry("b", 1);
    }

    @Test
    @DisplayName("남은 질의는 server down 오류다")
    void remainingQueriesAreDown() {
      // then
      assertThat(report.rows().getLast().b().hits().error()).contains("server down");
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("B가 띄울 때마다 둘째 호출에서 죽고 연속 실패 상한이 2면")
  class DiesBetweenSuccesses extends Situation {
    @Override
    Map<String, String> a() throws IOException {
      return answering("a", "d1");
    }

    @Override
    Map<String, String> b() throws IOException {
      Map<String, String> env = answering("b", "d1");
      env.put("FAKE_DIE_AFTER", "2");
      return env;
    }

    @Override
    int maxConsecutiveFailures() {
      return 2;
    }

    @Test
    @DisplayName("성공 사이의 실패는 연속으로 세지 않는다")
    void successResetsCount() {
      // then
      assertThat(report.summary().restarts()).containsEntry("b", 2);
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("B가 셋째 호출에서 JSON이 아닌 줄을 내면")
  class Garbage extends Situation {
    @Override
    Map<String, String> a() throws IOException {
      return answering("a", "d1");
    }

    @Override
    Map<String, String> b() throws IOException {
      Map<String, String> env = answering("b", "d1");
      env.put("FAKE_GARBAGE_AFTER", "3");
      return env;
    }

    @Test
    @DisplayName("그 질의만 오류다")
    void onlyThatQueryFails() {
      // then
      assertThat(report.summary().errors()).isEqualTo(1);
    }

    @Test
    @DisplayName("오류 메시지에 서버 출력 조각이 없다")
    void noServerOutputInError() {
      // then
      assertThat(report.rows().get(2).b().hits().error().orElseThrow()).doesNotContain("warning");
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("B가 셋째 호출에서 잘린 JSON을 내면")
  class Truncated extends Situation {
    @Override
    Map<String, String> a() throws IOException {
      return answering("a", "d1");
    }

    @Override
    Map<String, String> b() throws IOException {
      Map<String, String> env = answering("b", "d1");
      env.put("FAKE_TRUNCATED_AFTER", "3");
      return env;
    }

    @Test
    @DisplayName("그 질의만 오류다")
    void onlyThatQueryFails() {
      // then
      assertThat(report.summary().errors()).isEqualTo(1);
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("B가 JSON-RPC 오류로만 답하면")
  class CallErrors extends Situation {
    @Override
    Map<String, String> a() throws IOException {
      return answering("a", "d1");
    }

    @Override
    Map<String, String> b() throws IOException {
      Map<String, String> env = answering("b", "d1");
      env.put("FAKE_CALL_ERROR", "1");
      return env;
    }

    @Test
    @DisplayName("모든 질의가 오류다")
    void allErrors() {
      // then
      assertThat(report.summary().errors()).isEqualTo(QUERIES.size());
    }
  }
}
