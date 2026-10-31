package io.scos.harness.evaluation.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** rg·git를 실제로 실행한다. 둘 중 하나라도 없으면 건너뛰지 않고 실패한다. rg는 SCOS_RG(기본 rg)로 찾는다. */
@DisplayName("baseline 명령은 합성 자료로 rg·git 기준선을 잰다")
class BaselineMainTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RG = System.getenv().getOrDefault("SCOS_RG", "rg");

  @TempDir Path dir;

  @BeforeAll
  static void toolsAreAvailable() {
    runnable(RG, "rg 없음");
    runnable("git", "git 없음");
  }

  private static void runnable(String executable, String problem) {
    try {
      Process process =
          new ProcessBuilder(executable, "--version").redirectErrorStream(true).start();
      process.getInputStream().readAllBytes();
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0)
        throw new IllegalStateException("given 준비 실패: " + problem);
    } catch (IOException e) {
      throw new IllegalStateException("given 준비 실패: " + problem, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("given 준비 실패: " + problem, e);
    }
  }

  private record Outcome(int code, String err) {}

  private Outcome run(Map<String, String> environment, String... args) {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code =
        BaselineMain.run(
            args,
            environment,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Outcome(code, err.toString(StandardCharsets.UTF_8));
  }

  private Outcome run(String... args) {
    return run(Map.of("SCOS_RG", RG), args);
  }

  private static Path resource(String name) throws URISyntaxException {
    return Path.of(BaselineMainTest.class.getResource("/baseline/" + name).toURI());
  }

  /** 합성 커밋 두 개짜리 저장소. 두 번째 커밋 메시지에만 sprocket이 있다. 두 번째 커밋 sha를 돌려준다. */
  private String repoWithSprocketCommit(Path repo) throws Exception {
    Files.createDirectories(repo);
    git(repo, "init", "-q");
    Files.writeString(repo.resolve("a.txt"), "widget\n");
    git(repo, "add", "a.txt");
    git(repo, "commit", "-q", "-m", "feat: add widget");
    Files.writeString(repo.resolve("a.txt"), "widget\nmore\n");
    git(repo, "commit", "-q", "-a", "-m", "fix: tune sprocket");
    return git(repo, "rev-parse", "HEAD").trim();
  }

  private String git(Path repo, String... args) throws Exception {
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                "git",
                "-C",
                repo.toString(),
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@example.com",
                "-c",
                "commit.gpgsign=false"));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0)
      throw new IllegalStateException("given 준비 실패: git " + String.join(" ", args) + output);
    return output;
  }

  /** 합성 projects·pack·git 저장소로 한 번 재고 출력 디렉터리를 돌려준다. 실패하면 준비 실패다. */
  private Path measureSynthetic() throws Exception {
    Path repo = dir.resolve("repo");
    repoWithSprocketCommit(repo);
    Path out = dir.resolve("out");
    Outcome outcome =
        run(
            "--pack", resource("pack.json").toString(),
            "--projects", resource("projects").toString(),
            "--git", "repo=" + repo,
            "--out", out.toString());
    if (outcome.code() != 0)
      throw new IllegalStateException(
          "given 준비 실패: baseline 종료 " + outcome.code() + " " + outcome.err());
    return out;
  }

  private static JsonNode queryLine(Path out, int index) throws IOException {
    return JSON.readTree(Files.readAllLines(out.resolve("queries.jsonl")).get(index));
  }

  @Test
  @DisplayName("합성 projects에서 핵심어 매치가 많은 세션이 1위다")
  void ranksByMatchCount() throws Exception {
    // given
    Path out = measureSynthetic();

    // when
    JsonNode summary = JSON.readTree(out.resolve("summary.json").toFile());

    // then
    assertThat(summary.get("recall").asDouble()).isEqualTo(1.0);
    assertThat(summary.get("mrr").asDouble()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("서브에이전트 매치는 부모 세션에 합쳐져 매치가 더 적은 다른 세션보다 앞선다")
  void subagentMatchesJoinParentSession() throws Exception {
    // given
    Path out = measureSynthetic();

    // when
    JsonNode first = queryLine(out, 0);

    // then
    assertThat(first.get("rg").get(0).asText()).isEqualTo("transcript/d/s1");
  }

  @Test
  @DisplayName("메모리 파일도 문서로 순위에 오른다")
  void memoryFileIsRanked() throws Exception {
    // given
    Path out = measureSynthetic();

    // when
    JsonNode second = queryLine(out, 1);

    // then
    assertThat(second.get("rg").get(0).asText()).isEqualTo("memory/d/m.md");
  }

  @Test
  @DisplayName("출처별 점수를 따로 싣는다")
  void reportsScoresBySource() throws Exception {
    // given
    Path out = measureSynthetic();

    // when
    JsonNode bySource = JSON.readTree(out.resolve("summary.json").toFile()).get("bySource");

    // then — 합성 커밋 메시지에는 핵심어가 없다
    assertThat(bySource.at("/rg/mrr").asDouble()).isEqualTo(1.0);
    assertThat(bySource.at("/git/recall").asDouble()).isZero();
  }

  @Test
  @DisplayName("질의 줄에 kind를 싣는다")
  void queryLineCarriesKind() throws Exception {
    // given
    Path out = measureSynthetic();

    // when
    JsonNode second = queryLine(out, 1);

    // then
    assertThat(second.get("kind").asText()).isEqualTo("resume");
  }

  @Test
  @DisplayName("git log로만 찾을 수 있는 커밋은 git 기준선이 잡는다")
  void gitLogFindsCommit() throws Exception {
    // given
    Path repo = dir.resolve("repo");
    String sha = repoWithSprocketCommit(repo);
    Path pack = dir.resolve("git-pack.json");
    Files.writeString(
        pack,
        """
        {"name":"n","version":1,"queries":[{"query":"q","kind":"why",
         "expectedDocumentIds":["git/repo/%s"],"baseline":{"terms":["nomatch"],"gitTerms":["SPROCKET"]}}]}
        """
            .formatted(sha.substring(0, 8)));
    Path out = dir.resolve("out");

    // when
    run(
        "--pack", pack.toString(),
        "--projects", resource("projects").toString(),
        "--git", "repo=" + repo,
        "--out", out.toString());

    // then
    JsonNode summary = JSON.readTree(out.resolve("summary.json").toFile());
    assertThat(summary.at("/bySource/git/mrr").asDouble()).isEqualTo(1.0);
    assertThat(summary.at("/bySource/rg/recall").asDouble()).isZero();
  }

  @Test
  @DisplayName("질의 원문은 --include-query-text일 때만 쓴다")
  void queryTextOnlyOnRequest() throws Exception {
    // given
    Path plain = dir.resolve("plain");
    Path withText = dir.resolve("with-text");

    // when
    run(
        "--pack",
        resource("pack.json").toString(),
        "--projects",
        resource("projects").toString(),
        "--out",
        plain.toString());
    run(
        "--pack",
        resource("pack.json").toString(),
        "--projects",
        resource("projects").toString(),
        "--out",
        withText.toString(),
        "--include-query-text");

    // then
    assertThat(Files.readString(plain.resolve("queries.jsonl"))).doesNotContain("alpha plan");
    assertThat(Files.readString(withText.resolve("queries.jsonl")))
        .contains("what was the alpha plan");
  }

  @Test
  @DisplayName("필수 옵션이 없으면 종료 2이고 결과 파일을 쓰지 않는다")
  void missingOptionIsUsageError() throws Exception {
    // when
    Outcome outcome = run("--pack", resource("pack.json").toString());

    // then
    assertThat(outcome.code()).isEqualTo(2);
    assertThat(outcome.err()).contains("--projects is required");
  }

  @Test
  @DisplayName("baseline.terms가 잘못된 판정셋은 종료 2다")
  void badPackIsUsageError() throws Exception {
    // given
    Path pack = dir.resolve("bad.json");
    Files.writeString(pack, "{\"queries\":[{\"query\":\"q\",\"expectedDocumentIds\":[\"a\"]}]}");

    // when
    Outcome outcome =
        run(
            "--pack",
            pack.toString(),
            "--projects",
            resource("projects").toString(),
            "--out",
            dir.resolve("o").toString());

    // then
    assertThat(outcome.code()).isEqualTo(2);
    assertThat(outcome.err()).contains("query 0: baseline.terms");
  }

  @Test
  @DisplayName("rg를 실행할 수 없으면 종료 3이다")
  void missingRgIsExecutionError() throws Exception {
    // when
    Outcome outcome =
        run(
            Map.of("SCOS_RG", dir.resolve("no-such-rg").toString()),
            "--pack",
            resource("pack.json").toString(),
            "--projects",
            resource("projects").toString(),
            "--out",
            dir.resolve("o").toString());

    // then
    assertThat(outcome.code()).isEqualTo(3);
  }
}
