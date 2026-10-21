package io.scos.harness.o2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.harness.testing.FakeMcpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 비교 명령(o2Compare)의 인자·종료 코드·결과 파일·자식 환경. 서버는 실행 파일 하나로 받는다 — 여기서는 가짜 MCP 서버를 띄우는 셸 스크립트다. 종료 코드: 0
 * 통과, 1 기준 위반, 3 실행 오류(결과는 부분), 2 사용법.
 */
@DisplayName("비교 명령은 판정을 종료 코드로 알리고 결과를 안전하게 남긴다(O2)")
class ParallelComparisonMainTest {
  static final int PASS = 0;
  static final int VIOLATIONS = 1;
  static final int ERRORS = 3;

  @TempDir Path dir;
  final Map<String, String> environment = new HashMap<>();

  /** B 서버 행동: n번째 호출에서 죽는다. */
  static String diesOnCall(int n) {
    return "export FAKE_DIE_AFTER=" + n;
  }

  /** B 서버 행동: 받은 SCOS_QUERY_LOG를 log에 적는다. */
  static String recordsEnvTo(Path log) {
    return "export FAKE_ENV_LOG='" + log + "'";
  }

  /** FAKE_RESPONSE로 정해진 hits를 내는 가짜 서버를 띄우는 실행 파일. extra는 스크립트 안에서 더 내보낼 변수. */
  Path server(String name, String extra, String... documentIds) throws Exception {
    String result = ParallelComparisonProcessTest.searchResult(documentIds);
    String java =
        Path.of(
                System.getProperty("harness.java.home", System.getProperty("java.home")),
                "bin",
                "java")
            .toString();
    Path script = dir.resolve(name + ".sh");
    Files.writeString(
        script,
        "#!/bin/bash\nexport FAKE_RESPONSE='"
            + result.replace("'", "'\\''")
            + "'\n"
            + extra
            + "\nexec '"
            + java
            + "' -cp '"
            + System.getProperty("java.class.path")
            + "' "
            + FakeMcpServer.class.getName()
            + "\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }

  Path queries() throws Exception {
    return Files.writeString(
        dir.resolve("q.jsonl"),
        """
        {"ts":"2026-10-16T00:00:00.000Z","query":"합성 질의 하나","topDocumentIds":[],"topScore":0}
        {"ts":"2026-10-17T00:00:00.000Z","query":"합성 질의 둘","topDocumentIds":[],"topScore":0}
        """);
  }

  int run(String bExtra, String... extra) throws Exception {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--queries",
                queries().toString(),
                "--a-name",
                "ts",
                "--a-command",
                server("a", "", "d1", "d2").toString(),
                "--b-name",
                "java",
                "--b-command",
                server("b", bExtra, "d1", "d3").toString(),
                "--out",
                dir.resolve("out").toString(),
                "--warmup",
                "0",
                "--timeout-seconds",
                "5"));
    args.addAll(List.of(extra));
    return ParallelComparisonMain.run(args.toArray(String[]::new), environment);
  }

  @Test
  @DisplayName("기준이 없고 오류가 없으면 0으로 끝난다")
  void passesWithoutThresholds() throws Exception {
    // when
    int code = run("");

    // then
    assertThat(code).isEqualTo(PASS);
  }

  @Test
  @DisplayName("기준을 밑돌면 1로 끝난다")
  void violatedThresholdExitsOne() throws Exception {
    // when
    int code = run("", "--min-mean-jaccard", "0.8");

    // then
    assertThat(code).isEqualTo(VIOLATIONS);
  }

  @Nested
  @DisplayName("겹침이 낮은 질의를 분류 대상으로만 뽑으면")
  class ListLowOverlap {
    int listLowOverlap() throws Exception {
      return run("", "--list-jaccard-below", "0.5");
    }

    @Test
    @DisplayName("0으로 끝난다")
    void lowOverlapListIsNotViolation() throws Exception {
      // when
      int code = listLowOverlap();

      // then
      assertThat(code).isEqualTo(PASS);
    }

    @Test
    @DisplayName("low-overlap.jsonl에 양쪽 문서 id로 남는다")
    void lowOverlapFileHasDocumentIds() throws Exception {
      // when
      listLowOverlap();

      // then
      assertThat(Files.readString(dir.resolve("out/low-overlap.jsonl")))
          .contains("\"aDocumentIds\":[\"d1\",\"d2\"]");
    }

    @Test
    @DisplayName("low-overlap.jsonl에 질의 원문이 없다")
    void lowOverlapFileOmitsQueryText() throws Exception {
      // when
      listLowOverlap();

      // then
      assertThat(Files.readString(dir.resolve("out/low-overlap.jsonl"))).doesNotContain("합성 질의");
    }
  }

  @Nested
  @DisplayName("한쪽 서버가 호출 중 죽으면")
  class ServerDies {
    @Test
    @DisplayName("3으로 끝난다(실행 오류, 결과는 부분)")
    void exitsThree() throws Exception {
      // when
      int code = run(diesOnCall(1));

      // then
      assertThat(code).isEqualTo(ERRORS);
    }

    @Test
    @DisplayName("그래도 결과 파일을 쓴다")
    void stillWritesResults() throws Exception {
      // when
      run(diesOnCall(1));

      // then
      assertThat(dir.resolve("out/summary.json")).exists();
    }
  }

  @Test
  @DisplayName("기간 필터는 그 기간의 질의만 비교한다")
  void filtersByDate() throws Exception {
    // when
    run("", "--since", "2026-10-17", "--until", "2026-10-18");

    // then
    assertThat(Files.readAllLines(dir.resolve("out/rows.jsonl"))).hasSize(1);
  }

  @Test
  @DisplayName("자식 서버에는 SCOS_QUERY_LOG를 빈 값으로 넘긴다(운영 질의 로그 오염 방지)")
  void disablesQueryLogInChildren() throws Exception {
    // given
    environment.put("SCOS_QUERY_LOG", "/operational/mcp-queries.jsonl");

    // when
    run(recordsEnvTo(dir.resolve("env.log")));

    // then
    assertThat(Files.readString(dir.resolve("env.log"))).isEqualTo("SCOS_QUERY_LOG=\n");
  }

  @Test
  @DisplayName("서버 stderr 파일은 600이다")
  void stderrFileIs600() throws Exception {
    // when
    run("");

    // then
    assertThat(
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve("out/b-stderr.log"))))
        .isEqualTo("rw-------");
  }

  @Test
  @DisplayName("이미 있던 서버 stderr 파일도 600으로 고친다")
  void existingStderrFileTightened() throws Exception {
    // given
    Path log = Files.createDirectories(dir.resolve("out")).resolve("b-stderr.log");
    Files.writeString(log, "");
    Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-r--r--"));

    // when
    run("");

    // then
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log)))
        .isEqualTo("rw-------");
  }

  @Test
  @DisplayName("질의 파일이 없으면 사용법 오류다")
  void missingQueriesFileIsUsageError() {
    // given
    String[] args = {
      "--queries",
      dir.resolve("absent.jsonl").toString(),
      "--a-command",
      "a.sh",
      "--b-command",
      "b.sh",
      "--out",
      dir.resolve("out").toString()
    };

    // when·then
    assertThatThrownBy(() -> ParallelComparisonMain.run(args, environment))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("값이 빠진 옵션은 사용법 오류다")
  void missingValueIsUsageError() {
    // when·then
    assertThatThrownBy(() -> ParallelComparisonMain.parse(new String[] {"--limit"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("--limit needs a value");
  }
}
