package io.scos.harness.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.scos.harness.mcp.JsonRpcStdioClient;
import io.scos.harness.o2.ComparisonQuery;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 평가 명령(harness `./gradlew evaluate -Pevaluate.args="…"`, docs/public/evaluation.md). 판정셋 하나와 MCP
 * 서버 실행 파일 하나를 받아 Recall@k·MRR@k·nDCG@k를 재고, 하한을 주면 게이트로 쓴다. 자식 환경에는 SCOS_QUERY_LOG를 빈 값으로 넣어 평가
 * 질의가 질의 로그에 쌓이지 않게 한다. 서버 stderr는 출력 디렉터리의 server-stderr.log(600)로 가거나 --no-server-stderr로 버린다.
 *
 * <p>종료 코드: 0 = 하한 통과(또는 하한 없음), 1 = 하한 위반, 2 = 사용법 오류(결과 파일을 쓰지 않는다), 3 = 실행 오류가 있음(결과는 부분 — 오류
 * 질의는 행에 남는다).
 */
public final class EvaluationMain {
  static final int PASS = 0;
  static final int VIOLATIONS = 1;
  static final int USAGE = 2;
  static final int ERRORS = 3;

  private static final List<String> FLAGS = List.of("include-query-text", "no-server-stderr");
  private static final List<String> VALUES =
      List.of(
          "pack",
          "server-command",
          "out",
          "k",
          "limit",
          "min-recall",
          "min-mrr",
          "min-ndcg",
          "timeout-seconds",
          "effort");
  private static final List<String> EFFORTS = List.of("low", "medium", "high");

  private EvaluationMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.getenv(), System.out, System.err));
  }

  static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
    return exitCode(() -> evaluate(args, environment), out, err);
  }

  /** 평가 한 번. */
  interface Run {
    EvaluationReport get() throws IOException;
  }

  /** 평가 결과·예외 → 종료 코드. */
  static int exitCode(Run run, PrintStream out, PrintStream err) {
    try {
      EvaluationReport report = run.get();
      out.println(report.line());
      return switch (report.verdict()) {
        case ERRORS -> ERRORS;
        case VIOLATIONS -> VIOLATIONS;
        case PASS, NO_FLOORS -> PASS;
      };
    } catch (IllegalArgumentException e) {
      err.println("evaluate: " + e.getMessage());
      return USAGE;
    } catch (IOException | RuntimeException e) {
      // 예외 종류만 — 메시지에 서버 출력이나 질의 원문이 실릴 수 있다
      err.println("evaluate: " + e.getClass().getSimpleName());
      return ERRORS;
    }
  }

  static EvaluationReport evaluate(String[] args, Map<String, String> environment)
      throws IOException {
    Map<String, String> opts = parse(args);
    Path out = Path.of(required(opts, "out"));
    Path command = Path.of(required(opts, "server-command"));
    Evaluation.Options options =
        new Evaluation.Options(
            integer(opts, "k", 5),
            integer(opts, "limit", 10),
            Duration.ofSeconds(positive(opts, "timeout-seconds", 120)),
            new EvaluationFloors(
                floor(opts, "min-recall"), floor(opts, "min-mrr"), floor(opts, "min-ndcg")),
            opts.containsKey("include-query-text"),
            effort(opts));
    List<ComparisonQuery> queries = pack(Path.of(required(opts, "pack")));
    Files.createDirectories(out);
    Map<String, String> childEnv = new HashMap<>(environment);
    childEnv.put("SCOS_QUERY_LOG", "");
    Path log = opts.containsKey("no-server-stderr") ? null : stderrLog(out);
    EvaluationReport report =
        new Evaluation(options)
            .run(
                queries,
                new JsonRpcStdioClient.Launch(
                    List.of(command.toString()),
                    childEnv,
                    command.toAbsolutePath().getParent(),
                    log));
    report.write(out);
    return report;
  }

  /**
   * 판정셋 v1: name·version이 있고 queries[]마다 비어 있지 않은 expectedDocumentIds가 있어야 한다. 질의
   * 로그(SCOS_QUERY_LOG)는 정답이 없으므로 거부한다. 그 밖의 필드(docs·slice·evidenceText 등)는 읽지 않는다.
   */
  static List<ComparisonQuery> pack(Path file) throws IOException {
    if (!Files.isRegularFile(file)) throw new IllegalArgumentException("--pack file not found");
    String content = Files.readString(file);
    if (ComparisonQuery.detect(content) != ComparisonQuery.Format.PACK)
      throw new IllegalArgumentException("evaluate needs a judgment pack, not a query log");
    JsonNode root;
    try {
      root = JsonRpcStdioClient.mapper().readTree(content);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("pack is not JSON");
    }
    if (!present(root.path("name")) || !present(root.path("version")))
      throw new IllegalArgumentException("pack needs name and version");
    List<ComparisonQuery> queries = ComparisonQuery.load(file, ComparisonQuery.Format.PACK);
    // 정답 id는 원본 JSON에서 본다 — ComparisonQuery는 o2Compare 동작대로 수·null도 문자열로 바꿔 읽는다
    JsonNode packed = root.path("queries");
    for (int i = 0; i < packed.size(); i++) {
      JsonNode ids = packed.get(i).path("expectedDocumentIds");
      if (!ids.isArray() || ids.isEmpty())
        throw new IllegalArgumentException("query " + i + " has no expectedDocumentIds");
      for (JsonNode id : ids)
        if (!id.isTextual() || id.asText().isBlank())
          throw new IllegalArgumentException(
              "query " + i + " has an expectedDocumentIds entry that is not a non-empty string");
    }
    return queries;
  }

  /** 비어 있지 않은 문자열이나 수. */
  private static boolean present(JsonNode node) {
    return node.isNumber() || (node.isTextual() && !node.asText().isBlank());
  }

  /** 서버 stderr에는 질의 원문이 들어갈 수 있다 — 운영자만 읽게 600. */
  private static Path stderrLog(Path out) throws IOException {
    Path log = out.resolve("server-stderr.log");
    if (!Files.exists(log))
      Files.createFile(
          log, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    else Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
    return log;
  }

  /**
   * --name value 쌍. 값 없는 플래그는 include-query-text·no-server-stderr. 모르는 옵션은 거부한다 — 오타 난 하한이 조용히 빠지지
   * 않게.
   */
  static Map<String, String> parse(String[] args) {
    Map<String, String> opts = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--"))
        throw new IllegalArgumentException("unexpected argument " + args[i]);
      String name = args[i].substring(2);
      if (FLAGS.contains(name)) {
        opts.put(name, "true");
        continue;
      }
      if (!VALUES.contains(name)) throw new IllegalArgumentException("unknown option --" + name);
      if (i + 1 >= args.length) throw new IllegalArgumentException("--" + name + " needs a value");
      opts.put(name, args[++i]);
    }
    return opts;
  }

  private static int integer(Map<String, String> opts, String name, int fallback) {
    String value = opts.get(name);
    if (value == null) return fallback;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--" + name + " must be an integer");
    }
  }

  private static int positive(Map<String, String> opts, String name, int fallback) {
    int value = integer(opts, name, fallback);
    if (value <= 0) throw new IllegalArgumentException("--" + name + " must be positive");
    return value;
  }

  /** 지표 하한: [0, 1]의 유한한 수. NaN은 어떤 비교도 거짓이라 하한이 조용히 꺼지므로 거부한다. */
  private static Optional<Double> floor(Map<String, String> opts, String name) {
    return Optional.ofNullable(opts.get(name))
        .map(
            v -> {
              double value;
              try {
                value = Double.parseDouble(v);
              } catch (NumberFormatException e) {
                value = Double.NaN;
              }
              if (!Double.isFinite(value) || value < 0 || value > 1)
                throw new IllegalArgumentException(
                    "--" + name + " must be a finite number in [0, 1]");
              return value;
            });
  }

  /** search_context effort: low|medium|high. 생략하면 인자를 보내지 않는다. */
  private static Optional<String> effort(Map<String, String> opts) {
    String value = opts.get("effort");
    if (value != null && !EFFORTS.contains(value))
      throw new IllegalArgumentException("--effort must be low, medium or high");
    return Optional.ofNullable(value);
  }

  private static String required(Map<String, String> opts, String name) {
    String value = opts.get(name);
    if (value == null) throw new IllegalArgumentException("--" + name + " is required");
    return value;
  }
}
