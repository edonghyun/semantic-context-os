package io.scos.harness.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.scos.harness.mcp.JsonRpcStdioClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * 평가 결과: 질의별 행, 오류가 아닌 질의의 평균, 하한 위반과 판정. 파일은 summary.json(요약)과 queries.jsonl(질의마다 한 줄)이다. 질의 원문은
 * --include-query-text일 때만 싣는다 — 기본은 질의 번호(판정셋 queries 배열의 0부터 센 위치)뿐이다. 응답 본문(문서 내용)은 어디에도 싣지 않는다
 * — 문서 id와 지표만.
 */
public record EvaluationReport(
    Evaluation.Options options,
    List<Row> rows,
    Optional<Scores> mean,
    int errors,
    List<String> violations,
    Verdict verdict) {
  static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** 한 질의(또는 평균)의 지표. rr은 역순위이고 그 평균이 MRR이다. */
  public record Scores(double recall, double rr, double ndcg) {}

  /** 질의 한 줄. 지표는 호출이 성공했을 때만 있고, 오류면 error만 있다. */
  public record Row(
      int index,
      Optional<String> query,
      List<String> expected,
      List<String> ranked,
      Optional<Scores> scores,
      Optional<String> error,
      Optional<AppliedEffort> applied,
      Optional<Double> latencyMs) {}

  /** 실행 오류가 있으면 ERRORS(평균은 부분), 아니면 하한 위반, 하한 통과, 하한 없음. */
  public enum Verdict {
    PASS,
    VIOLATIONS,
    ERRORS,
    NO_FLOORS
  }

  static EvaluationReport of(Evaluation.Options options, List<Row> rows) {
    List<Scores> scored = rows.stream().flatMap(r -> r.scores().stream()).toList();
    int errors = rows.size() - scored.size();
    Optional<Scores> mean =
        scored.isEmpty()
            ? Optional.empty()
            : Optional.of(
                new Scores(
                    average(scored, Scores::recall),
                    average(scored, Scores::rr),
                    average(scored, Scores::ndcg)));
    List<String> violations =
        mean.map(m -> options.floors().violations(m.recall(), m.rr(), m.ndcg())).orElse(List.of());
    Verdict verdict =
        errors > 0
            ? Verdict.ERRORS
            : !violations.isEmpty()
                ? Verdict.VIOLATIONS
                : options.floors().any() ? Verdict.PASS : Verdict.NO_FLOORS;
    return new EvaluationReport(options, List.copyOf(rows), mean, errors, violations, verdict);
  }

  private static double average(List<Scores> scored, ToDoubleFunction<Scores> metric) {
    return scored.stream().mapToDouble(metric).average().orElseThrow();
  }

  public void write(Path dir) throws IOException {
    Files.createDirectories(dir);
    ObjectMapper json = JsonRpcStdioClient.mapper();
    StringBuilder lines = new StringBuilder();
    for (Row row : rows) lines.append(json.writeValueAsString(row(row))).append('\n');
    Files.writeString(dir.resolve("queries.jsonl"), lines);
    Files.writeString(dir.resolve("summary.json"), PRETTY.writeValueAsString(summary()) + "\n");
  }

  ObjectNode summary() {
    ObjectNode o = PRETTY.createObjectNode();
    o.put("queries", rows.size());
    o.put("k", options.k());
    mean.ifPresent(
        m -> {
          o.put("recall", m.recall());
          o.put("mrr", m.rr());
          o.put("ndcg", m.ndcg());
        });
    o.put("errors", errors);
    options.effort().ifPresent(e -> o.put("effort", e));
    LatencySummary.of(rows.stream().flatMap(r -> r.latencyMs().stream()).toList())
        .ifPresent(
            l -> {
              ObjectNode latency = o.putObject("latencyMs");
              latency.put("median", round3(l.median()));
              latency.put("p95", round3(l.p95()));
            });
    if (rows.stream().anyMatch(r -> r.applied().isPresent()))
      o.put(
          "escalated",
          rows.stream()
              .filter(r -> r.applied().filter(AppliedEffort::escalated).isPresent())
              .count());
    ObjectNode floors = o.putObject("floors");
    options.floors().recall().ifPresent(v -> floors.put("recall", v));
    options.floors().mrr().ifPresent(v -> floors.put("mrr", v));
    options.floors().ndcg().ifPresent(v -> floors.put("ndcg", v));
    o.put("verdict", verdictName());
    return o;
  }

  static ObjectNode row(Row r) {
    ObjectNode o = JsonRpcStdioClient.mapper().createObjectNode();
    o.put("index", r.index());
    r.query().ifPresent(q -> o.put("query", q));
    r.expected().forEach(o.putArray("expected")::add);
    r.ranked().forEach(o.putArray("ranked")::add);
    r.scores()
        .ifPresent(
            s -> {
              o.put("recall", s.recall());
              o.put("rr", s.rr());
              o.put("ndcg", s.ndcg());
            });
    r.error().ifPresent(e -> o.put("error", e));
    r.applied()
        .ifPresent(
            a -> {
              o.put("appliedEffort", a.effort());
              o.put("escalated", a.escalated());
            });
    r.latencyMs().ifPresent(ms -> o.put("latencyMs", round3(ms)));
    return o;
  }

  private static double round3(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }

  /** stdout 한 줄 요약. */
  public String line() {
    StringBuilder s = new StringBuilder("evaluate: queries=").append(rows.size());
    s.append(" k=").append(options.k());
    mean.ifPresent(
        m ->
            s.append(
                String.format(
                    Locale.ROOT, " recall=%.4f mrr=%.4f ndcg=%.4f", m.recall(), m.rr(), m.ndcg())));
    s.append(" errors=").append(errors);
    if (!violations.isEmpty()) s.append(" below-floor=").append(String.join(",", violations));
    return s.append(" verdict=").append(verdictName()).toString();
  }

  private String verdictName() {
    return verdict.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
