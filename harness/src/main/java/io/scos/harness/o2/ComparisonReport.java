package io.scos.harness.o2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.scos.harness.o2.ParallelComparison.Distribution;
import io.scos.harness.o2.ParallelComparison.Measured;
import io.scos.harness.o2.ParallelComparison.Report;
import io.scos.harness.o2.ParallelComparison.Row;
import io.scos.harness.perf.LatencyBenchmark.Percentiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * 비교 결과 파일: rows.jsonl(질의마다 한 줄), summary.json(요약·임계값·위반·판정), report.md(사람이 읽는 표),
 * low-overlap.jsonl(원인 분류 대상 — 해시·지표·양쪽 상위 문서 id). 질의 원문은 옵션을 켰을 때만 싣는다 — 기본은 queryHash(sha256 앞
 * 16자)뿐이다. 응답 본문(문서 내용)은 어디에도 싣지 않는다 — 문서 id·지표·지연만. 문서 id는 원문이 아니고 rows.jsonl에도 있으므로 분류 목록에 늘
 * 싣는다(분류에 필요하다).
 */
public final class ComparisonReport {
  static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private ComparisonReport() {}

  public static void write(Report report, Path dir) throws IOException {
    Files.createDirectories(dir);
    StringBuilder rows = new StringBuilder();
    for (Row row : report.rows())
      rows.append(ComparisonQuery.JSON.writeValueAsString(row(row))).append('\n');
    Files.writeString(dir.resolve("rows.jsonl"), rows);
    Files.writeString(dir.resolve("summary.json"), JSON.writeValueAsString(summary(report)) + "\n");
    Files.writeString(dir.resolve("report.md"), markdown(report));
    StringBuilder low = new StringBuilder();
    for (Row row : report.rows())
      if (row.lowOverlap())
        low.append(ComparisonQuery.JSON.writeValueAsString(lowOverlap(row))).append('\n');
    Files.writeString(dir.resolve("low-overlap.jsonl"), low);
  }

  static ObjectNode lowOverlap(Row r) {
    ObjectNode o = ComparisonQuery.JSON.createObjectNode();
    o.put("index", r.index());
    o.put("queryHash", r.queryHash());
    r.query().ifPresent(q -> o.put("query", q));
    r.jaccard().ifPresent(v -> o.put("jaccard", v));
    r.rbo().ifPresent(v -> o.put("rbo", v));
    r.a().hits().documentIds().forEach(o.putArray("aDocumentIds")::add);
    r.b().hits().documentIds().forEach(o.putArray("bDocumentIds")::add);
    return o;
  }

  static ObjectNode row(Row r) {
    ObjectNode o = ComparisonQuery.JSON.createObjectNode();
    o.put("index", r.index());
    o.put("queryHash", r.queryHash());
    r.query().ifPresent(q -> o.put("query", q));
    side(o, "a", r.a());
    side(o, "b", r.b());
    r.jaccard().ifPresent(v -> o.put("jaccard", v));
    r.rbo().ifPresent(v -> o.put("rbo", v));
    r.top1Same().ifPresent(v -> o.put("top1Same", v));
    o.put("firstHitBasis", r.firstHitBasis().name().toLowerCase(Locale.ROOT));
    r.firstHitRankA().ifPresent(v -> o.put("firstHitRankA", v));
    r.firstHitRankB().ifPresent(v -> o.put("firstHitRankB", v));
    r.firstHitDelta().ifPresent(v -> o.put("firstHitDelta", v));
    o.put("firstHitRegression", r.firstHitRegression());
    o.put("lowOverlap", r.lowOverlap());
    r.violations().forEach(o.putArray("violations")::add);
    return o;
  }

  private static void side(ObjectNode o, String name, Measured m) {
    ObjectNode s = o.putObject(name);
    s.put("latencyMs", round(m.latencyMs()));
    m.hits()
        .error()
        .ifPresentOrElse(
            e -> s.put("error", e),
            () -> {
              s.put("hits", m.hits().documentIds().size());
              m.hits().documentIds().forEach(s.putArray("documentIds")::add);
            });
  }

  static ObjectNode summary(Report report) {
    var s = report.summary();
    var opt = report.options();
    ObjectNode o = JSON.createObjectNode();
    o.put("a", report.aName());
    o.put("b", report.bName());
    ObjectNode options = o.putObject("options");
    options.put("limit", opt.limit());
    options.put("k", opt.k());
    options.put("rboP", opt.rboP());
    options.put("warmupCalls", opt.warmupCalls());
    options.put("maxConsecutiveFailures", opt.maxConsecutiveFailures());
    options.put("includeQueryText", opt.includeQueryText());
    ObjectNode thresholds = o.putObject("thresholds");
    var t = opt.thresholds();
    t.minJaccard().ifPresent(v -> thresholds.put("minJaccard", v));
    t.minMeanJaccard().ifPresent(v -> thresholds.put("minMeanJaccard", v));
    t.minRbo().ifPresent(v -> thresholds.put("minRbo", v));
    t.maxFirstHitRegressions().ifPresent(v -> thresholds.put("maxFirstHitRegressions", v));
    t.maxP95Ratio().ifPresent(v -> thresholds.put("maxP95Ratio", v));
    t.maxP95Ms().ifPresent(v -> thresholds.put("maxP95Ms", v));
    t.lowOverlapBelow().ifPresent(v -> thresholds.put("lowOverlapBelow", v));
    o.put("queries", s.queries());
    o.put("compared", s.compared());
    o.put("errors", s.errors());
    ObjectNode restarts = o.putObject("restarts");
    s.restarts().forEach(restarts::put);
    distribution(o, "jaccard", s.jaccard());
    distribution(o, "rbo", s.rbo());
    s.top1Agreement().ifPresent(v -> o.put("top1Agreement", v));
    o.put("expectedQueries", s.expectedQueries());
    o.put("firstHitRegressions", s.firstHitRegressions());
    o.put("lowOverlap", s.lowOverlap());
    latency(o, "latencyA", s.latencyA());
    latency(o, "latencyB", s.latencyB());
    s.p95Ratio().ifPresent(v -> o.put("p95Ratio", v));
    ObjectNode violations = o.putObject("violations");
    s.violations().forEach(violations::put);
    o.put("verdict", s.verdict().name().toLowerCase(Locale.ROOT).replace('_', '-'));
    return o;
  }

  private static void distribution(ObjectNode o, String name, Optional<Distribution> d) {
    d.ifPresent(
        v -> {
          ObjectNode n = o.putObject(name);
          n.put("samples", v.samples());
          n.put("mean", v.mean());
          n.put("p50", v.p50());
          n.put("min", v.min());
        });
  }

  private static void latency(ObjectNode o, String name, Optional<Percentiles> p) {
    p.ifPresent(
        v -> {
          ObjectNode n = o.putObject(name);
          n.put("samples", v.samples());
          n.put("p50", round(v.p50()));
          n.put("p95", round(v.p95()));
          n.put("max", round(v.max()));
        });
  }

  static String markdown(Report report) {
    var s = report.summary();
    StringBuilder md = new StringBuilder();
    md.append("# O2 병행 비교: ")
        .append(report.aName())
        .append(" (A) 대 ")
        .append(report.bName())
        .append(" (B)\n\n");
    md.append("| 항목 | 값 |\n| --- | --- |\n");
    md.append("| 질의 | ").append(s.queries()).append(" (비교 ").append(s.compared());
    md.append(", 오류 ").append(s.errors()).append(") |\n");
    s.jaccard()
        .ifPresent(
            d ->
                md.append(
                    fmt(
                        "| Jaccard@%d 평균 / 중앙 / 최소 | %.3f / %.3f / %.3f |\n",
                        report.options().k(), d.mean(), d.p50(), d.min())));
    s.rbo()
        .ifPresent(
            d ->
                md.append(
                    fmt(
                        "| RBO(p=%.2f) 평균 / 중앙 / 최소 | %.3f / %.3f / %.3f |\n",
                        report.options().rboP(), d.mean(), d.p50(), d.min())));
    s.top1Agreement().ifPresent(v -> md.append(fmt("| 1위 일치 비율 | %.3f |\n", v)));
    s.latencyA()
        .ifPresent(
            p -> md.append(fmt("| A 지연 p50 / p95 (ms) | %.1f / %.1f |\n", p.p50(), p.p95())));
    s.latencyB()
        .ifPresent(
            p -> md.append(fmt("| B 지연 p50 / p95 (ms) | %.1f / %.1f |\n", p.p50(), p.p95())));
    s.p95Ratio().ifPresent(v -> md.append(fmt("| p95 비(B/A) | %.2f |\n", v)));
    md.append("| 정답 있는 질의 / B 첫 적중 회귀 | ")
        .append(s.expectedQueries())
        .append(" / ")
        .append(s.firstHitRegressions())
        .append(" |\n");
    md.append("| 원인 분류 대상(겹침 낮음) | ").append(s.lowOverlap()).append(" |\n");
    md.append("| 서버 재기동 | ").append(s.restarts()).append(" |\n");
    md.append("| 위반 | ").append(s.violations()).append(" |\n");
    md.append("| 판정 | ").append(s.verdict()).append(" |\n\n");
    md.append("| # | 질의 | Jaccard | RBO | 1위 | 첫 적중 A→B | A ms | B ms | 위반 |\n");
    md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
    for (Row r : report.rows())
      md.append(
          fmt(
              "| %d | %s | %s | %s | %s | %s→%s | %.1f | %.1f | %s |\n",
              r.index(),
              r.query().orElse(r.queryHash()).replace("|", "\\|"),
              r.jaccard().map(v -> fmt("%.3f", v)).orElse("—"),
              r.rbo().map(v -> fmt("%.3f", v)).orElse("—"),
              r.top1Same().map(v -> v ? "같음" : "다름").orElse("—"),
              r.firstHitRankA().map(String::valueOf).orElse("—"),
              r.firstHitRankB().map(String::valueOf).orElse("—"),
              r.a().latencyMs(),
              r.b().latencyMs(),
              String.join(",", r.violations())));
    return md.toString();
  }

  private static String fmt(String pattern, Object... args) {
    return String.format(Locale.ROOT, pattern, args);
  }

  private static double round(double ms) {
    return Math.round(ms * 10) / 10.0;
  }
}
