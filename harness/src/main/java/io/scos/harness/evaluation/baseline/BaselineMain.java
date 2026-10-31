package io.scos.harness.evaluation.baseline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;

/**
 * 기준선 명령(harness `./gradlew baseline -Pbaseline.args="…"`, docs/public/evaluation.md). 판정셋의
 * baseline.terms로 `rg -uu`와 `git log`가 정답 문서를 얼마나 앞에 세우는지 재서, 이후 로컬 MCP 클라이언트와 비교할 기준선을 만든다. rg 실행
 * 파일은 환경변수 SCOS_RG(기본 rg)다.
 *
 * <p>종료 코드: 0 = 완료, 2 = 사용법 오류(결과 파일을 쓰지 않는다), 3 = 실행 오류(rg·git 실행 실패 등).
 */
public final class BaselineMain {
  static final int OK = 0;
  static final int USAGE = 2;
  static final int ERRORS = 3;

  private static final List<String> FLAGS = List.of("include-query-text");
  private static final List<String> REPEATED = List.of("docs", "git");
  private static final List<String> VALUES = List.of("pack", "projects", "out", "k");
  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private BaselineMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.getenv(), System.out, System.err));
  }

  static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
    try {
      out.println(measure(args, environment));
      return OK;
    } catch (IllegalArgumentException e) {
      err.println("baseline: " + e.getMessage());
      return USAGE;
    } catch (IOException | RuntimeException e) {
      // 예외 종류만 — 메시지에 질의 원문이 실릴 수 있다
      err.println("baseline: " + e.getClass().getSimpleName());
      return ERRORS;
    }
  }

  /** 한 줄 요약을 돌려준다. */
  static String measure(String[] args, Map<String, String> environment) throws IOException {
    Options options = Options.parse(args);
    List<BaselinePack.Query> queries = BaselinePack.read(options.pack());
    RipgrepRanking ripgrep = new RipgrepRanking(environment.getOrDefault("SCOS_RG", "rg"));
    GitLogRanking gitLog = new GitLogRanking("git");
    List<Row> rows = new ArrayList<>();
    for (BaselinePack.Query query : queries) {
      List<String> rg = ripgrep.rank(query.terms(), options.projects(), options.docs());
      List<String> git =
          options.repos().isEmpty() ? List.of() : gitLog.rank(query.gitTerms(), options.repos());
      rows.add(new Row(query, rg, git, options.k()));
    }
    Files.createDirectories(options.out());
    Files.writeString(options.out().resolve("queries.jsonl"), queriesJsonl(rows, options));
    SummaryOutput summary = summary(rows, options.k());
    Files.writeString(options.out().resolve("summary.json"), MAPPER.writeValueAsString(summary));
    return "baseline: queries=%d k=%d recall=%.3f mrr=%.3f ndcg=%.3f"
        .formatted(
            rows.size(),
            options.k(),
            mean(rows, r -> r.combined().recall()),
            mean(rows, r -> r.combined().reciprocalRank()),
            mean(rows, r -> r.combined().ndcg()));
  }

  /** 질의 하나의 rg·git 결과와 점수. */
  private record Row(BaselinePack.Query query, List<String> rg, List<String> git, int k) {
    BaselineScore combined() {
      return BaselineScore.of(rg, git, query.expected(), k);
    }

    BaselineScore rgOnly() {
      return BaselineScore.single(rg, query.expected(), k);
    }

    BaselineScore gitOnly() {
      return BaselineScore.single(git, query.expected(), k);
    }
  }

  /** 질의의 역순위 키는 rr이고 출처별·평균 점수는 mrr이다. null인 선택 필드만 생략한다. */
  private record QueryOutput(
      int index,
      @JsonInclude(Include.NON_NULL) @Nullable String kind,
      @JsonInclude(Include.NON_NULL) @Nullable String query,
      List<String> expected,
      List<String> rg,
      List<String> git,
      double recall,
      double rr,
      double ndcg,
      ScoresOutput rgScores,
      ScoresOutput gitScores) {
    static QueryOutput of(int index, Row row, boolean includeQueryText) {
      BaselineScore combined = row.combined();
      return new QueryOutput(
          index,
          row.query().kind(),
          includeQueryText ? row.query().query() : null,
          row.query().expected().stream().sorted().toList(),
          top(row.rg(), row.k()),
          top(row.git(), row.k()),
          combined.recall(),
          combined.reciprocalRank(),
          combined.ndcg(),
          new ScoresOutput(row.rgOnly()),
          new ScoresOutput(row.gitOnly()));
    }
  }

  private record ScoresOutput(double recall, double mrr, double ndcg) {
    ScoresOutput(BaselineScore score) {
      this(score.recall(), score.reciprocalRank(), score.ndcg());
    }
  }

  private record BySourceOutput(ScoresOutput rg, ScoresOutput git) {}

  private record SummaryOutput(
      int queries, int k, double recall, double mrr, double ndcg, BySourceOutput bySource) {}

  private static String queriesJsonl(List<Row> rows, Options options) throws IOException {
    StringBuilder lines = new StringBuilder();
    var writer = MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT);
    for (int i = 0; i < rows.size(); i++) {
      var line = QueryOutput.of(i, rows.get(i), options.includeQueryText());
      lines.append(writer.writeValueAsString(line)).append('\n');
    }
    return lines.toString();
  }

  private static SummaryOutput summary(List<Row> rows, int k) {
    BaselineScore combined = scoresOf(rows, Row::combined);
    return new SummaryOutput(
        rows.size(),
        k,
        combined.recall(),
        combined.reciprocalRank(),
        combined.ndcg(),
        new BySourceOutput(
            new ScoresOutput(scoresOf(rows, Row::rgOnly)),
            new ScoresOutput(scoresOf(rows, Row::gitOnly))));
  }

  private static BaselineScore scoresOf(List<Row> rows, Function<Row, BaselineScore> pick) {
    return new BaselineScore(
        mean(rows, r -> pick.apply(r).recall()),
        mean(rows, r -> pick.apply(r).reciprocalRank()),
        mean(rows, r -> pick.apply(r).ndcg()));
  }

  private static double mean(List<Row> rows, ToDoubleFunction<Row> value) {
    return rows.stream().mapToDouble(value).average().orElse(0);
  }

  private static List<String> top(List<String> ranked, int k) {
    return ranked.subList(0, Math.min(k, ranked.size()));
  }

  /** 명령줄. 모르는 옵션은 거부한다. */
  private record Options(
      Path pack,
      Path projects,
      Map<String, Path> docs,
      Map<String, Path> repos,
      Path out,
      int k,
      boolean includeQueryText) {

    static Options parse(String[] args) {
      Map<String, String> values = new LinkedHashMap<>();
      Map<String, Path> docs = new LinkedHashMap<>();
      Map<String, Path> repos = new LinkedHashMap<>();
      boolean includeQueryText = false;
      for (int i = 0; i < args.length; i++) {
        if (!args[i].startsWith("--"))
          throw new IllegalArgumentException("unexpected argument " + args[i]);
        String name = args[i].substring(2);
        if (FLAGS.contains(name)) {
          includeQueryText = true;
          continue;
        }
        if (!VALUES.contains(name) && !REPEATED.contains(name))
          throw new IllegalArgumentException("unknown option --" + name);
        if (i + 1 >= args.length)
          throw new IllegalArgumentException("--" + name + " needs a value");
        String value = args[++i];
        if (name.equals("docs")) named(name, value, docs);
        else if (name.equals("git")) named(name, value, repos);
        else values.put(name, value);
      }
      Path projects = Path.of(required(values, "projects"));
      if (!Files.isDirectory(projects))
        throw new IllegalArgumentException("--projects directory not found");
      return new Options(
          Path.of(required(values, "pack")),
          projects,
          docs,
          repos,
          Path.of(required(values, "out")),
          k(values.get("k")),
          includeQueryText);
    }

    private static void named(String option, String value, Map<String, Path> into) {
      int eq = value.indexOf('=');
      if (eq <= 0 || eq == value.length() - 1)
        throw new IllegalArgumentException("--" + option + " must be <name>=<dir>");
      String name = value.substring(0, eq);
      Path dir = Path.of(value.substring(eq + 1));
      if (!Files.isDirectory(dir))
        throw new IllegalArgumentException("--" + option + " " + name + ": directory not found");
      if (into.putIfAbsent(name, dir) != null)
        throw new IllegalArgumentException("--" + option + " " + name + " given twice");
    }

    private static int k(String value) {
      if (value == null) return 5;
      try {
        int k = Integer.parseInt(value);
        if (k > 0) return k;
      } catch (NumberFormatException e) {
        // 아래에서 같은 메시지로 거부한다
      }
      throw new IllegalArgumentException("--k must be a positive integer");
    }

    private static String required(Map<String, String> values, String name) {
      String value = values.get(name);
      if (value == null) throw new IllegalArgumentException("--" + name + " is required");
      return value;
    }
  }
}
