package io.scos.harness.o2;

import io.scos.harness.mcp.JsonRpcStdioClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * O2 비교 명령(harness `./gradlew o2Compare -Po2.args="…"`, harness/README.md). 두 MCP 서버를 실행 파일 하나씩으로
 * 받는다. 자식 환경에는 SCOS_QUERY_LOG를 빈 값으로 넣어 재생 질의가 운영 질의 로그에 쌓이지 않게 한다(--keep-query-log로 끔, F11 리뷰 I3).
 * 서버 stderr는 출력 디렉터리의 a-stderr.log·b-stderr.log(600)로 가거나 --no-server-stderr로 버린다.
 *
 * <p>종료 코드: 0 = 기준 통과(또는 기준 없음), 1 = 기준 위반, 3 = 실행 오류가 있음(결과는 부분 — 오류 질의는 행에 남는다), 2 = 사용법 오류(질의 파일
 * 없음 포함). 결과 파일은 어느 경우든(사용법 오류 제외) 쓴다.
 */
public final class ParallelComparisonMain {
  static final int PASS = 0;
  static final int VIOLATIONS = 1;
  static final int USAGE = 2;
  static final int ERRORS = 3;

  private ParallelComparisonMain() {}

  public static void main(String[] args) {
    int code;
    try {
      code = run(args, System.getenv());
    } catch (IllegalArgumentException e) {
      System.err.println("o2Compare: " + e.getMessage());
      code = USAGE;
    } catch (IOException e) {
      System.err.println("o2Compare: " + e.getClass().getSimpleName());
      code = ERRORS;
    }
    System.exit(code);
  }

  static int run(String[] args, Map<String, String> environment) throws IOException {
    Map<String, String> opts = parse(args);
    Path out = Path.of(required(opts, "out"));
    int limit = integer(opts, "limit", 10);
    ParallelComparison.Options options =
        new ParallelComparison.Options(
            limit,
            integer(opts, "k", limit),
            decimal(opts, "rbo-p").orElse(RankingMetrics.DEFAULT_RBO_P),
            new Thresholds(
                decimal(opts, "min-jaccard"),
                decimal(opts, "min-mean-jaccard"),
                decimal(opts, "min-rbo"),
                optional(opts, "max-first-hit-regressions")
                    .map(v -> integer(v, "max-first-hit-regressions")),
                decimal(opts, "max-p95-ratio"),
                decimal(opts, "max-p95-ms"),
                decimal(opts, "list-jaccard-below")),
            Duration.ofSeconds(integer(opts, "timeout-seconds", 120)),
            integer(opts, "warmup", 1),
            integer(opts, "max-consecutive-failures", 3),
            opts.containsKey("include-query-text"));
    Path input = Path.of(required(opts, "queries"));
    // 경로 실수는 실행 오류(3, 결과는 부분)가 아니라 사용법 오류다 — 결과 파일도 쓰지 않는다(F11 재확인 r2)
    if (!Files.isRegularFile(input)) throw new IllegalArgumentException("--queries file not found");
    List<ComparisonQuery> queries =
        ComparisonQuery.between(
            ComparisonQuery.load(input, format(opts)),
            optional(opts, "since").map(v -> instant(v, "since")),
            optional(opts, "until").map(v -> instant(v, "until")));
    Files.createDirectories(out);
    boolean stderr = !opts.containsKey("no-server-stderr");
    Map<String, String> childEnv = new HashMap<>(environment);
    if (!opts.containsKey("keep-query-log")) childEnv.put("SCOS_QUERY_LOG", "");
    ParallelComparison.Report report =
        new ParallelComparison(options)
            .run(
                queries,
                side(opts, "a", out, childEnv, stderr),
                side(opts, "b", out, childEnv, stderr));
    ComparisonReport.write(report, out);
    return switch (report.summary().verdict()) {
      case ERRORS -> ERRORS;
      case VIOLATIONS -> VIOLATIONS;
      case PASS, NO_THRESHOLDS -> PASS;
    };
  }

  static ParallelComparison.Side side(
      Map<String, String> opts, String key, Path out, Map<String, String> env, boolean stderr)
      throws IOException {
    Path command = Path.of(required(opts, key + "-command"));
    Path log = null;
    if (stderr) {
      // 서버 stderr에는 질의 원문이 들어갈 수 있다(노출 규칙 대상) — 운영자만 읽게 600(F11 리뷰 m5)
      log = out.resolve(key + "-stderr.log");
      if (!Files.exists(log))
        Files.createFile(
            log,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      else Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
    }
    return new ParallelComparison.Side(
        opts.getOrDefault(key + "-name", key),
        new JsonRpcStdioClient.Launch(
            List.of(command.toString()), env, command.toAbsolutePath().getParent(), log));
  }

  /** --name value 쌍. 값 없는 플래그는 include-query-text·keep-query-log·no-server-stderr. */
  static Map<String, String> parse(String[] args) {
    List<String> flags = List.of("include-query-text", "keep-query-log", "no-server-stderr");
    Map<String, String> opts = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--"))
        throw new IllegalArgumentException("unexpected argument " + args[i]);
      String name = args[i].substring(2);
      if (flags.contains(name)) {
        opts.put(name, "true");
        continue;
      }
      if (i + 1 >= args.length) throw new IllegalArgumentException("--" + name + " needs a value");
      opts.put(name, args[++i]);
    }
    return opts;
  }

  /** YYYY-MM-DD(UTC 그날 0시) 또는 ISO instant. --since는 포함, --until은 제외. */
  static Instant instant(String value, String name) {
    try {
      return value.length() == 10
          ? LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant()
          : Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("--" + name + " must be YYYY-MM-DD or an ISO instant");
    }
  }

  private static ComparisonQuery.Format format(Map<String, String> opts) {
    try {
      return ComparisonQuery.Format.valueOf(
          opts.getOrDefault("format", "auto").toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("--format must be auto, query-log or pack");
    }
  }

  private static int integer(Map<String, String> opts, String name, int fallback) {
    return optional(opts, name).map(v -> integer(v, name)).orElse(fallback);
  }

  private static int integer(String value, String name) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--" + name + " must be an integer");
    }
  }

  private static Optional<Double> decimal(Map<String, String> opts, String name) {
    return optional(opts, name)
        .map(
            v -> {
              try {
                return Double.parseDouble(v);
              } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " must be a number");
              }
            });
  }

  private static String required(Map<String, String> opts, String name) {
    String value = opts.get(name);
    if (value == null) throw new IllegalArgumentException("--" + name + " is required");
    return value;
  }

  private static Optional<String> optional(Map<String, String> opts, String name) {
    return Optional.ofNullable(opts.get(name));
  }
}
