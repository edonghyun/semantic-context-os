package io.scos.harness.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.scos.harness.evaluation.EvaluationReport.Row;
import io.scos.harness.mcp.JsonRpcStdioClient;
import io.scos.harness.o2.ComparisonQuery;
import io.scos.harness.o2.SearchHits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 판정셋 평가(5b): MCP 서버 하나에 판정셋 질의를 stdio로 보내고(search_context), 질의마다 이진 관련도 지표를 잰다. 서버는 프로세스 하나를 띄워 모든
 * 질의에 다시 쓴다.
 *
 * <p>호출이 실패하면(서버 종료·타임아웃·형식 위반·오류 응답) 그 질의만 오류로 남기고 서버를 다시 띄워 다음 질의를 잇는다. 연속 실패가 상한에 닿거나 다시 띄우지 못하면
 * 남은 질의는 "server down" 오류다. 오류 메시지에는 예외 종류만 싣는다(서버 출력 조각을 싣지 않는다). 평균은 오류가 아닌 질의로만 낸다.
 */
public final class Evaluation {
  /** 한 서버가 이만큼 연속으로 실패하면 더 띄우지 않는다(응답 없는 서버가 질의마다 timeout을 쓰지 않게). */
  static final int MAX_CONSECUTIVE_FAILURES = 3;

  /**
   * @param k 지표를 볼 상위 문서 수
   * @param limit search_context limit(유닛 hits 수 — 문서로 중복을 빼므로 문서 순위는 이보다 짧을 수 있다)
   * @param effort search_context effort 인자(low|medium|high) — 비어 있으면 보내지 않는다
   */
  public record Options(
      int k,
      int limit,
      Duration timeout,
      EvaluationFloors floors,
      boolean includeQueryText,
      Optional<String> effort) {
    public Options {
      if (k <= 0 || limit <= 0)
        throw new IllegalArgumentException("--k and --limit must be positive");
    }
  }

  private final Options options;

  public Evaluation(Options options) {
    this.options = options;
  }

  /** 질의는 모두 정답이 비어 있지 않아야 한다(EvaluationMain이 먼저 거른다). */
  public EvaluationReport run(List<ComparisonQuery> queries, JsonRpcStdioClient.Launch launch) {
    try (Server server = new Server(launch)) {
      server.startOrMarkDown();
      List<Row> rows = new ArrayList<>();
      for (int i = 0; i < queries.size(); i++)
        rows.add(row(i, queries.get(i), server.call(queries.get(i))));
      return EvaluationReport.of(options, rows);
    }
  }

  /** 호출 한 번의 결과: 순위, 서버가 알린 applied, 걸린 시간(ms). */
  record Call(SearchHits hits, Optional<AppliedEffort> applied, Optional<Double> latencyMs) {
    static Call failed(String reason) {
      return new Call(SearchHits.failed(reason), Optional.empty(), Optional.empty());
    }
  }

  Row row(int index, ComparisonQuery q, Call call) {
    SearchHits hits = call.hits();
    List<String> expected = List.copyOf(new LinkedHashSet<>(q.expectedDocumentIds().orElseThrow()));
    Optional<String> text = options.includeQueryText() ? Optional.of(q.text()) : Optional.empty();
    if (!hits.ok())
      return new Row(
          index,
          text,
          expected,
          List.of(),
          Optional.empty(),
          hits.error(),
          Optional.empty(),
          call.latencyMs());
    List<String> ranked = hits.documentIds();
    int k = options.k();
    return new Row(
        index,
        text,
        expected,
        ranked,
        Optional.of(
            new EvaluationReport.Scores(
                RelevanceMetrics.recallAt(ranked, expected, k),
                RelevanceMetrics.reciprocalRankAt(ranked, expected, k),
                RelevanceMetrics.ndcgAt(ranked, expected, k))),
        Optional.empty(),
        call.applied(),
        call.latencyMs());
  }

  /** 평가할 서버: 실패하면 다시 띄우고, 연속 실패가 상한에 닿거나 다시 띄우지 못하면 down이다. */
  private final class Server implements AutoCloseable {
    private final JsonRpcStdioClient.Launch launch;
    private JsonRpcStdioClient client;
    private boolean down;
    private int consecutiveFailures;

    Server(JsonRpcStdioClient.Launch launch) {
      this.launch = launch;
    }

    void startOrMarkDown() {
      client = new JsonRpcStdioClient(launch, options.timeout());
      try {
        client.start();
      } catch (Exception e) {
        client.close();
        client = null;
        down = true;
      }
    }

    Call call(ComparisonQuery q) {
      if (down) return Call.failed("server down");
      ObjectNode args = JsonRpcStdioClient.mapper().createObjectNode();
      args.put("query", q.text());
      args.put("limit", options.limit());
      options.effort().ifPresent(effort -> args.put("effort", effort));
      try {
        long start = System.nanoTime();
        JsonNode result = client.callTool("search_context", args);
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        SearchHits hits = SearchHits.parse(result);
        consecutiveFailures = 0;
        return new Call(
            hits, AppliedEffort.parse(result), hits.ok() ? Optional.of(ms) : Optional.empty());
      } catch (Exception e) {
        // 예외 종류만 — 파싱 예외 메시지에는 서버 출력 조각이 실린다
        recover();
        return Call.failed("call failed: " + e.getClass().getSimpleName());
      }
    }

    /** 실패 뒤 스트림이 어긋났을 수 있다 — 상한 안이면 프로세스를 새로 띄운다. */
    private void recover() {
      client.close();
      client = null;
      if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
        down = true;
        return;
      }
      startOrMarkDown();
    }

    @Override
    public void close() {
      if (client != null) client.close();
    }
  }
}
