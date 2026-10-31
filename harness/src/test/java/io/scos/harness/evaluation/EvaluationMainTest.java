package io.scos.harness.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.scos.harness.mcp.JsonRpcStdioClient;
import io.scos.harness.testing.FakeServerLauncher;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 평가 명령(evaluate)의 인자·종료 코드·결과 파일. 서버는 실행 파일 하나로 받는다 — 여기서는 가짜 MCP 서버를 띄우는 셸 스크립트이고, 어느 질의에나 [d1,
 * d2]를 낸다. 기본 판정셋은 정답 d1·d2인 질의 둘이라 recall 1, MRR 0.75, nDCG 약 0.815다. 종료 코드: 0 통과, 1 하한 위반, 2 사용법,
 * 3 실행 오류.
 */
@DisplayName("평가 명령은 판정셋 지표를 하한과 견주어 종료 코드로 알린다(5b)")
class EvaluationMainTest {
  static final int PASS = 0;
  static final int VIOLATIONS = 1;
  static final int USAGE = 2;
  static final int ERRORS = 3;

  static final String PACK =
      """
      {"name":"synthetic","version":1,"docs":[],"queries":[
        {"query":"합성 질의 하나","expectedDocumentIds":["d1"],"slice":"s"},
        {"query":"합성 질의 둘","expectedDocumentIds":["d2","d2"]}]}
      """;

  @TempDir Path dir;
  final Map<String, String> environment = new HashMap<>();
  final Map<String, String> serverEnv = new HashMap<>();

  /** 한 번의 실행: 종료 코드와 표준 오류. */
  record Outcome(int code, String err) {}

  Outcome run(String pack, String... extra) throws Exception {
    return runWith(Files.writeString(dir.resolve("pack.json"), pack), extra);
  }

  Outcome runWith(Path pack, String... extra) throws Exception {
    serverEnv.putIfAbsent("FAKE_RESPONSE", FakeServerLauncher.searchResult("d1", "d2"));
    List<String> args =
        new ArrayList<>(
            List.of(
                "--pack",
                pack.toString(),
                "--server-command",
                FakeServerLauncher.script(dir, "server", serverEnv).toString(),
                "--out",
                dir.resolve("out").toString(),
                "--timeout-seconds",
                "5"));
    args.addAll(List.of(extra));
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code =
        EvaluationMain.run(
            args.toArray(String[]::new),
            environment,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Outcome(code, err.toString(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("하한을 넘으면 종료 0이고 summary.json을 쓴다")
  void aboveFloorsPassesAndWritesSummary() throws Exception {
    // when
    Outcome outcome = run(PACK, "--min-recall", "0.9", "--min-mrr", "0.7", "--min-ndcg", "0.8");

    // then
    assertThat(outcome.code()).isEqualTo(PASS);
    assertThat(Files.readString(dir.resolve("out/summary.json")))
        .contains("\"verdict\" : \"pass\"");
  }

  @Test
  @DisplayName("하한에 못 미치면 종료 1이다")
  void belowFloorExitsOne() throws Exception {
    // when
    Outcome outcome = run(PACK, "--min-mrr", "0.8");

    // then
    assertThat(outcome.code()).isEqualTo(VIOLATIONS);
  }

  @Test
  @DisplayName("서버 호출 오류가 있으면 종료 3이다")
  void serverErrorExitsThree() throws Exception {
    // given
    serverEnv.put("FAKE_DIE_AFTER", "1");

    // when
    Outcome outcome = run(PACK);

    // then
    assertThat(outcome.code()).isEqualTo(ERRORS);
  }

  @Test
  @DisplayName("질의 로그를 주면 judgment pack이 필요하다며 종료 2다")
  void queryLogIsUsageError() throws Exception {
    // when
    Outcome outcome =
        run(
            """
            {"ts":"2026-10-16T00:00:00.000Z","query":"합성 질의","topDocumentIds":[],"topScore":0}
            """);

    // then
    assertThat(outcome.code()).isEqualTo(USAGE);
    assertThat(outcome.err()).contains("evaluate needs a judgment pack");
  }

  @Test
  @DisplayName("name이나 version이 없는 판정셋은 종료 2다")
  void packWithoutNameOrVersionIsUsageError() throws Exception {
    // when
    Outcome outcome =
        run(
            "{\"name\":\"synthetic\",\"queries\":[{\"query\":\"q\",\"expectedDocumentIds\":[\"d1\"]}]}");

    // then
    assertThat(outcome.code()).isEqualTo(USAGE);
    assertThat(outcome.err()).contains("pack needs name and version");
  }

  @Test
  @DisplayName("정답이 빈 질의가 있으면 질의 번호를 말하며 종료 2다")
  void emptyExpectedIsUsageError() throws Exception {
    // when
    Outcome outcome =
        run(
            """
            {"name":"synthetic","version":1,"queries":[
              {"query":"q0","expectedDocumentIds":["d1"]},
              {"query":"q1","expectedDocumentIds":[]}]}
            """);

    // then
    assertThat(outcome.code()).isEqualTo(USAGE);
    assertThat(outcome.err()).contains("query 1 has no expectedDocumentIds");
  }

  @Test
  @DisplayName("판정셋 파일이 없으면 종료 2다")
  void missingPackIsUsageError() throws Exception {
    // when
    Outcome outcome = runWith(dir.resolve("absent.json"));

    // then
    assertThat(outcome.code()).isEqualTo(USAGE);
  }

  @Test
  @DisplayName("서버 호출 오류가 있어도 결과 파일에 질의별 error를 남긴다")
  void serverErrorStillWritesRowsWithError() throws Exception {
    // given
    serverEnv.put("FAKE_DIE_AFTER", "1");

    // when
    run(PACK);

    // then
    assertThat(Files.readString(dir.resolve("out/queries.jsonl")))
        .contains("\"index\":0", "\"error\":\"call failed: ");
  }

  @ParameterizedTest(name = "--min-recall {0}")
  @ValueSource(strings = {"NaN", "Infinity", "-0.1", "1.5"})
  @DisplayName("하한이 0과 1 사이의 유한한 수가 아니면 종료 2다")
  void floorOutsideUnitIntervalIsUsageError(String floor) throws Exception {
    // when
    Outcome outcome = run(PACK, "--min-recall", floor);

    // then
    assertThat(outcome.code()).isEqualTo(USAGE);
    assertThat(outcome.err()).contains("--min-recall must be a finite number in [0, 1]");
  }

  @ParameterizedTest(name = "--timeout-seconds {0}")
  @ValueSource(strings = {"0", "-1"})
  @DisplayName("시간 상한이 0 이하이면 종료 2다")
  void nonPositiveTimeoutIsUsageError(String seconds) throws Exception {
    // when
    Outcome outcome = run(PACK, "--timeout-seconds", seconds);

    // then
    assertThat(outcome.code()).isEqualTo(USAGE);
  }

  @ParameterizedTest(name = "expectedDocumentIds {0}")
  @ValueSource(strings = {"[1]", "[\"\"]", "[\"d1\",null]", "[\" \"]"})
  @DisplayName("정답 id가 비어 있지 않은 문자열이 아니면 질의 번호를 말하며 종료 2다")
  void nonStringExpectedIdIsUsageError(String ids) throws Exception {
    // when
    Outcome outcome =
        run(
            "{\"name\":\"synthetic\",\"version\":1,\"queries\":["
                + "{\"query\":\"q0\",\"expectedDocumentIds\":[\"d1\"]},"
                + "{\"query\":\"q1\",\"expectedDocumentIds\":"
                + ids
                + "}]}");

    // then
    assertThat(outcome.code()).isEqualTo(USAGE);
    assertThat(outcome.err()).contains("query 1 has an expectedDocumentIds entry");
  }

  @Test
  @DisplayName("그 밖의 실행 중 예외는 종료 3이다")
  void unexpectedRuntimeExceptionExitsThree() {
    // given
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    PrintStream stream = new PrintStream(sink, true, StandardCharsets.UTF_8);

    // when
    int code =
        EvaluationMain.exitCode(
            () -> {
              throw new IllegalStateException("unexpected");
            },
            stream,
            stream);

    // then
    assertThat(code).isEqualTo(ERRORS);
  }

  @Test
  @DisplayName("--include-query-text가 없으면 결과 파일에 질의 원문이 없다")
  void queryTextOmittedByDefault() throws Exception {
    // when
    run(PACK);

    // then
    assertThat(Files.readString(dir.resolve("out/queries.jsonl"))).doesNotContain("합성 질의");
  }

  @Test
  @DisplayName("자식 서버에는 SCOS_QUERY_LOG를 빈 값으로 넘긴다")
  void disablesQueryLogInChild() throws Exception {
    // given
    environment.put("SCOS_QUERY_LOG", "/operational/mcp-queries.jsonl");
    serverEnv.put("FAKE_ENV_LOG", dir.resolve("env.log").toString());

    // when
    run(PACK);

    // then
    assertThat(Files.readString(dir.resolve("env.log"))).isEqualTo("SCOS_QUERY_LOG=\n");
  }

  @Test
  @DisplayName("--effort high는 search_context 인자에 effort를 실어 보낸다")
  void effortIsSentInToolArguments() throws Exception {
    // given
    serverEnv.put("FAKE_CALL_LOG", dir.resolve("calls.log").toString());

    // when
    Outcome outcome = run(PACK, "--effort", "high");

    // then
    assertThat(outcome.code()).isEqualTo(PASS);
    assertThat(Files.readString(dir.resolve("calls.log"))).contains("\"effort\":\"high\"");
  }

  @Test
  @DisplayName("--effort가 없으면 인자에 effort 키를 보내지 않는다")
  void effortIsOmittedByDefault() throws Exception {
    // given
    serverEnv.put("FAKE_CALL_LOG", dir.resolve("calls.log").toString());

    // when
    run(PACK);

    // then
    assertThat(Files.readString(dir.resolve("calls.log"))).contains("search_context");
    assertThat(Files.readString(dir.resolve("calls.log"))).doesNotContain("effort");
  }

  @ParameterizedTest(name = "--effort {0}")
  @ValueSource(strings = {"max", "HIGH", ""})
  @DisplayName("effort가 low·medium·high가 아니면 종료 2다")
  void unknownEffortIsUsageError(String effort) throws Exception {
    // when
    Outcome outcome = run(PACK, "--effort", effort);

    // then
    assertThat(outcome.code()).isEqualTo(USAGE);
    assertThat(outcome.err()).contains("--effort must be low, medium or high");
  }

  @Test
  @DisplayName("summary.json에 effort와 호출 지연 중앙값·p95를 쓴다")
  void summaryCarriesEffortAndLatency() throws Exception {
    // when
    run(PACK, "--effort", "low");

    // then
    JsonNode summary =
        JsonRpcStdioClient.mapper().readTree(dir.resolve("out/summary.json").toFile());
    assertThat(summary.path("effort").asText()).isEqualTo("low");
    assertThat(summary.path("latencyMs").path("median").isNumber()).isTrue();
    assertThat(summary.path("latencyMs").path("p95").isNumber()).isTrue();
  }

  @Test
  @DisplayName("effort를 주지 않으면 summary.json에 effort 키가 없다")
  void summaryOmitsEffortByDefault() throws Exception {
    // when
    run(PACK);

    // then
    assertThat(Files.readString(dir.resolve("out/summary.json"))).doesNotContain("\"effort\"");
  }

  @Test
  @DisplayName("응답의 applied를 질의 행에 남기고 상향된 질의 수를 summary에 센다")
  void appliedIsRecordedPerQueryAndEscalationsCounted() throws Exception {
    // given
    serverEnv.put(
        "FAKE_RESPONSE",
        "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"hits\\\":[{\\\"documentId\\\":\\\"d1\\\"}],"
            + "\\\"applied\\\":{\\\"effort\\\":\\\"low\\\",\\\"profile\\\":\\\"auto\\\",\\\"escalated\\\":true}}\"}]}");

    // when
    run(PACK, "--effort", "low");

    // then
    assertThat(Files.readString(dir.resolve("out/queries.jsonl")))
        .contains("\"appliedEffort\":\"low\"", "\"escalated\":true");
    assertThat(Files.readString(dir.resolve("out/summary.json"))).contains("\"escalated\" : 2");
  }
}
