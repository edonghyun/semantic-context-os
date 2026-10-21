package io.scos.harness.o2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.scos.harness.mcp.JsonRpcStdioClient;
import io.scos.harness.perf.LatencyBenchmark.Percentiles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * O2 병행 운영 비교(F11 T11.2): 같은 질의를 두 MCP 서버(A = 기준, 보통 TS; B = 후보, 보통 Java)에 stdio로 보내 search_context
 * 결과의 겹침·첫 적중·지연을 질의별로 기록하고 요약·판정한다. 서버는 프로세스 하나씩 띄워 모든 질의에 다시 쓴다. 질의마다 A·B를 번갈아 먼저 부른다.
 *
 * <p>견고성(F11 리뷰 I2): 한쪽 호출이 실패하면(서버 종료·타임아웃·JSON이 아닌 줄·잘린 JSON·오류 응답) 그 질의만 오류로 남기고, 서버를 다시 띄워 워밍업한
 * 뒤 다음 질의를 이어 간다. 연속 실패가 상한(maxConsecutiveFailures)에 닿거나 다시 띄우지도 못하면 그 쪽의 남은 질의는 "server down" 오류다.
 * 어느 경우에도 모든 질의의 행과 요약이 나온다. 오류 메시지에는 예외 종류만 싣는다(서버 출력 조각을 싣지 않는다).
 *
 * <p>첫 적중: 정답이 있으면(판정셋) 각 쪽에서 정답이 처음 나온 순위, 없으면 A의 1위 문서가 B의 몇 위인지(A는 1). firstHitDelta = B − A(양수면
 * B가 나쁨). 정답 있는 질의에서 B가 나쁜 것(둘 다 적중하고 B가 아래, 또는 A만 적중)을 "회귀"로 센다 — B가 더 나은 것은 회귀가 아니다.
 */
public final class ParallelComparison {
  /** 비교할 서버 하나: 보고서의 이름과 stdio 기동 방법. */
  public record Side(String name, JsonRpcStdioClient.Launch launch) {}

  /**
   * @param limit search_context limit(유닛 hits 수 — 문서로 중복을 빼므로 상위 limit hits 안의 문서다)
   * @param k 겹침을 볼 상위 문서 수
   * @param rboP RBO 가중
   * @param warmupCalls 측정 전과 다시 띄운 뒤에 쪽마다 첫 질의로 부르는 횟수(기록하지 않는다)
   * @param maxConsecutiveFailures 한 쪽이 이만큼 연속으로 실패하면 더 띄우지 않고 그 쪽을 down으로 둔다
   */
  public record Options(
      int limit,
      int k,
      double rboP,
      Thresholds thresholds,
      Duration timeout,
      int warmupCalls,
      int maxConsecutiveFailures,
      boolean includeQueryText) {
    public Options {
      if (limit <= 0 || k <= 0) throw new IllegalArgumentException("limit and k must be positive");
      if (rboP <= 0 || rboP >= 1) throw new IllegalArgumentException("rbo p must be in (0, 1)");
      if (warmupCalls < 0) throw new IllegalArgumentException("warmup calls must be >= 0");
      if (maxConsecutiveFailures < 1)
        throw new IllegalArgumentException("max consecutive failures must be >= 1");
    }
  }

  /** 첫 적중의 기준: 정답 문서 또는 A의 1위. */
  public enum FirstHitBasis {
    EXPECTED,
    A_TOP1
  }

  /** 질의 한 줄. 지표는 두 쪽 모두 성공했을 때만 있다. */
  public record Row(
      int index,
      String queryHash,
      Optional<String> query,
      Measured a,
      Measured b,
      Optional<Double> jaccard,
      Optional<Double> rbo,
      Optional<Boolean> top1Same,
      FirstHitBasis firstHitBasis,
      Optional<Integer> firstHitRankA,
      Optional<Integer> firstHitRankB,
      Optional<Integer> firstHitDelta,
      boolean firstHitRegression,
      boolean lowOverlap,
      List<String> violations) {
    public boolean compared() {
      return a.hits().ok() && b.hits().ok();
    }
  }

  /** 한 쪽 호출의 결과와 왕복 시간. */
  public record Measured(SearchHits hits, double latencyMs) {}

  /** 지표 하나의 분포(비교된 질의만). */
  public record Distribution(int samples, double mean, double p50, double min) {
    static Optional<Distribution> of(List<Double> values) {
      if (values.isEmpty()) return Optional.empty();
      Percentiles p = Percentiles.of(values);
      double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
      double min = values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
      return Optional.of(new Distribution(values.size(), mean, p.p50(), min));
    }
  }

  /** 판정: 실행 오류가 있으면 ERRORS(결과는 부분), 아니면 기준 위반, 기준 통과, 기준 없음. */
  public enum Verdict {
    PASS,
    VIOLATIONS,
    ERRORS,
    NO_THRESHOLDS
  }

  public record Summary(
      int queries,
      int compared,
      int errors,
      Map<String, Integer> restarts,
      Optional<Distribution> jaccard,
      Optional<Distribution> rbo,
      Optional<Double> top1Agreement,
      int expectedQueries,
      int firstHitRegressions,
      int lowOverlap,
      Optional<Percentiles> latencyA,
      Optional<Percentiles> latencyB,
      Optional<Double> p95Ratio,
      Map<String, Integer> violations,
      Verdict verdict) {}

  public record Report(
      String aName, String bName, Options options, List<Row> rows, Summary summary) {}

  private final Options options;

  public ParallelComparison(Options options) {
    this.options = options;
  }

  public Report run(List<ComparisonQuery> queries, Side a, Side b) {
    Optional<ComparisonQuery> warmup = queries.stream().findFirst();
    Server serverA = new Server(a, warmup);
    Server serverB = new Server(b, warmup);
    try {
      serverA.startOrMarkDown();
      serverB.startOrMarkDown();
      for (int i = 0; i < options.warmupCalls(); i++) {
        serverA.warmUpOnce();
        serverB.warmUpOnce();
      }
      List<Row> rows = new ArrayList<>();
      for (int i = 0; i < queries.size(); i++) {
        ComparisonQuery q = queries.get(i);
        Measured ma;
        Measured mb;
        if (i % 2 == 0) {
          ma = serverA.call(q);
          mb = serverB.call(q);
        } else {
          mb = serverB.call(q);
          ma = serverA.call(q);
        }
        rows.add(row(i, q, ma, mb));
      }
      Map<String, Integer> restarts = new LinkedHashMap<>();
      restarts.put(a.name(), serverA.restarts);
      restarts.put(b.name(), serverB.restarts);
      return new Report(a.name(), b.name(), options, rows, summarize(rows, restarts));
    } finally {
      serverA.close();
      serverB.close();
    }
  }

  /**
   * 한 쪽 서버: 실패하면 다시 띄워 워밍업하고(지연이 cold 기동을 재지 않게), 연속 실패가 상한에 닿거나 다시 띄우지 못하면 남은 호출을 "server down"으로
   * 돌려준다(응답하지 않는 서버가 질의마다 timeout을 쓰지 않게, F11 재확인 r3·r4).
   */
  private final class Server implements AutoCloseable {
    private final Side side;
    private final Optional<ComparisonQuery> warmup;
    private JsonRpcStdioClient client;
    private boolean down;
    private int consecutiveFailures;
    int restarts;

    Server(Side side, Optional<ComparisonQuery> warmup) {
      this.side = side;
      this.warmup = warmup;
    }

    void startOrMarkDown() {
      client = new JsonRpcStdioClient(side.launch(), options.timeout());
      try {
        client.start();
      } catch (Exception e) {
        client.close();
        client = null;
        down = true;
      }
    }

    /** 기록하지 않는 호출 하나. 실패하면 측정 호출과 같게 다시 띄운다. */
    void warmUpOnce() {
      warmup.ifPresent(this::call);
    }

    Measured call(ComparisonQuery q) {
      if (down) return new Measured(SearchHits.failed("server down"), 0);
      ObjectNode args = ComparisonQuery.JSON.createObjectNode();
      args.put("query", q.text());
      args.put("limit", options.limit());
      long started = System.nanoTime();
      SearchHits hits;
      try {
        hits = SearchHits.parse(client.callTool("search_context", args));
        consecutiveFailures = 0;
      } catch (Exception e) {
        // 예외 종류만 — 파싱 예외 메시지에는 서버 출력 조각이 실린다(F11 리뷰 m4)
        hits = SearchHits.failed("call failed: " + e.getClass().getSimpleName());
        recover();
      }
      return new Measured(hits, (System.nanoTime() - started) / 1e6);
    }

    /** 실패 뒤 스트림이 어긋났을 수 있다 — 상한 안이면 프로세스를 새로 띄우고 워밍업한다. */
    private void recover() {
      client.close();
      client = null;
      if (++consecutiveFailures >= options.maxConsecutiveFailures()) {
        down = true;
        return;
      }
      restarts++;
      startOrMarkDown();
      for (int i = 0; i < options.warmupCalls() && !down; i++) warmUpOnce();
    }

    @Override
    public void close() {
      if (client != null) client.close();
    }
  }

  /** 한 질의의 지표·첫 적중·분류·질의별 위반. 프로세스 없이도 부를 수 있다(테스트). */
  Row row(int index, ComparisonQuery q, Measured a, Measured b) {
    Optional<String> text = options.includeQueryText() ? Optional.of(q.text()) : Optional.empty();
    FirstHitBasis basis =
        q.expectedDocumentIds().isPresent() ? FirstHitBasis.EXPECTED : FirstHitBasis.A_TOP1;
    if (!(a.hits().ok() && b.hits().ok()))
      return new Row(
          index,
          q.hash(),
          text,
          a,
          b,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          basis,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          false,
          false,
          List.of("error"));
    List<String> left = a.hits().documentIds();
    List<String> right = b.hits().documentIds();
    double jaccard = RankingMetrics.jaccard(left, right, options.k());
    double rbo = RankingMetrics.rbo(left, right, options.k(), options.rboP());
    Optional<Boolean> top1 =
        left.isEmpty() || right.isEmpty()
            ? Optional.empty()
            : Optional.of(left.getFirst().equals(right.getFirst()));
    Optional<Integer> rankA;
    Optional<Integer> rankB;
    if (basis == FirstHitBasis.EXPECTED) {
      rankA = RankingMetrics.firstHitRank(left, q.expectedDocumentIds().orElseThrow());
      rankB = RankingMetrics.firstHitRank(right, q.expectedDocumentIds().orElseThrow());
    } else {
      rankA = left.isEmpty() ? Optional.empty() : Optional.of(1);
      rankB = left.isEmpty() ? Optional.empty() : RankingMetrics.rankOf(right, left.getFirst());
    }
    Optional<Integer> delta =
        rankA.isPresent() && rankB.isPresent()
            ? Optional.of(rankB.get() - rankA.get())
            : Optional.empty();
    boolean regression =
        basis == FirstHitBasis.EXPECTED
            && rankA.isPresent()
            && (rankB.isEmpty() || rankB.get() > rankA.get());
    Thresholds t = options.thresholds();
    boolean lowOverlap = t.lowOverlapBelow().filter(below -> jaccard < below).isPresent();
    List<String> violations = new ArrayList<>();
    t.minJaccard().filter(min -> jaccard < min).ifPresent(min -> violations.add("jaccard"));
    t.minRbo().filter(min -> rbo < min).ifPresent(min -> violations.add("rbo"));
    return new Row(
        index,
        q.hash(),
        text,
        a,
        b,
        Optional.of(jaccard),
        Optional.of(rbo),
        top1,
        basis,
        rankA,
        rankB,
        delta,
        regression,
        lowOverlap,
        List.copyOf(violations));
  }

  Summary summarize(List<Row> rows) {
    return summarize(rows, Map.of());
  }

  /** 비교된 질의만으로 분포를 내고, 지연은 성공한 호출만으로 낸다. */
  Summary summarize(List<Row> rows, Map<String, Integer> restarts) {
    List<Row> compared = rows.stream().filter(Row::compared).toList();
    List<Boolean> top1 = compared.stream().flatMap(r -> r.top1Same().stream()).toList();
    Optional<Percentiles> latencyA = latency(rows, Row::a);
    Optional<Percentiles> latencyB = latency(rows, Row::b);
    Optional<Double> ratio =
        latencyA.isPresent() && latencyB.isPresent() && latencyA.get().p95() > 0
            ? Optional.of(latencyB.get().p95() / latencyA.get().p95())
            : Optional.empty();
    Optional<Distribution> jaccard =
        Distribution.of(compared.stream().map(r -> r.jaccard().orElseThrow()).toList());
    int regressions = (int) compared.stream().filter(Row::firstHitRegression).count();
    int errors = rows.size() - compared.size();
    Thresholds t = options.thresholds();
    Map<String, Integer> violations = new LinkedHashMap<>();
    violations.put(
        "jaccard", (int) rows.stream().filter(r -> r.violations().contains("jaccard")).count());
    violations.put("rbo", (int) rows.stream().filter(r -> r.violations().contains("rbo")).count());
    violations.put(
        "meanJaccard",
        t.minMeanJaccard().isPresent()
                && jaccard.map(d -> d.mean() < t.minMeanJaccard().get()).orElse(false)
            ? 1
            : 0);
    violations.put(
        "firstHitRegressions",
        t.maxFirstHitRegressions().filter(max -> regressions > max).isPresent() ? 1 : 0);
    violations.put(
        "p95Ratio",
        t.maxP95Ratio().isPresent() && ratio.filter(r -> r > t.maxP95Ratio().get()).isPresent()
            ? 1
            : 0);
    violations.put(
        "p95Ms",
        t.maxP95Ms().isPresent() && latencyB.filter(p -> p.p95() > t.maxP95Ms().get()).isPresent()
            ? 1
            : 0);
    boolean violated = violations.values().stream().anyMatch(n -> n > 0);
    Verdict verdict =
        errors > 0
            ? Verdict.ERRORS
            : violated ? Verdict.VIOLATIONS : t.any() ? Verdict.PASS : Verdict.NO_THRESHOLDS;
    return new Summary(
        rows.size(),
        compared.size(),
        errors,
        restarts,
        jaccard,
        Distribution.of(compared.stream().map(r -> r.rbo().orElseThrow()).toList()),
        top1.isEmpty()
            ? Optional.empty()
            : Optional.of((double) top1.stream().filter(b -> b).count() / top1.size()),
        (int) compared.stream().filter(r -> r.firstHitBasis() == FirstHitBasis.EXPECTED).count(),
        regressions,
        (int) compared.stream().filter(Row::lowOverlap).count(),
        latencyA,
        latencyB,
        ratio,
        violations,
        verdict);
  }

  private static Optional<Percentiles> latency(List<Row> rows, Function<Row, Measured> side) {
    List<Double> ms =
        rows.stream().map(side).filter(m -> m.hits().ok()).map(Measured::latencyMs).toList();
    return ms.isEmpty() ? Optional.empty() : Optional.of(Percentiles.of(ms));
  }
}
