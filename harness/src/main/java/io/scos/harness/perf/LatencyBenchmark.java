package io.scos.harness.perf;

import com.fasterxml.jackson.databind.JsonNode;
import io.scos.harness.fixture.FixtureSpec;
import io.scos.harness.mcp.JsonRpcStdioClient;
import io.scos.harness.server.ServerLauncher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 같은 장비·같은 fixture·같은 카세트로 구현의 MCP stdio 왕복 시간을 잰다(계획 7절 성능 비교). cold = 프로세스 기동부터 initialize 응답까지,
 * warm = warm-up 라운드 뒤 측정 라운드의 호출별 왕복. 외부 API 없음(카세트). 응답 정확성은 javaTest·tsTest가 따로 본다 — 여기서는 시간만.
 */
public final class LatencyBenchmark {
  /** 한 구현의 측정 결과(밀리초). */
  public record Result(
      String implementation,
      double coldStartMs,
      int warmupRounds,
      int measuredRounds,
      int callsPerRound,
      Map<String, Percentiles> byTool,
      Percentiles all) {}

  public record Percentiles(int samples, double p50, double p95, double max) {
    public static Percentiles of(List<Double> ms) {
      List<Double> sorted = new ArrayList<>(ms);
      sorted.sort(Comparator.naturalOrder());
      return new Percentiles(
          sorted.size(), rank(sorted, 0.50), rank(sorted, 0.95), sorted.get(sorted.size() - 1));
    }

    /** nearest-rank 백분위. */
    static double rank(List<Double> sorted, double q) {
      int index = (int) Math.ceil(q * sorted.size()) - 1;
      return sorted.get(Math.max(0, index));
    }
  }

  private final FixtureSpec fixture;
  private final Duration timeout;

  public LatencyBenchmark(FixtureSpec fixture, Duration timeout) {
    this.fixture = fixture;
    this.timeout = timeout;
  }

  public Result measure(ServerLauncher launcher, int warmupRounds, int measuredRounds)
      throws IOException {
    Path work = Files.createTempDirectory("scos-latency-" + launcher.implementationId() + "-");
    try {
      Path store = launcher.prepare(fixture, work);
      ServerLauncher.HarnessEnv env =
          new ServerLauncher.HarnessEnv(
              fixture.nowIso(),
              fixture.cassettePath(),
              work.resolve("cassette-misses.jsonl"),
              fixture.spaceId());
      List<FixtureSpec.Call> calls = fixture.calls();
      Map<String, List<Double>> byTool = new LinkedHashMap<>();
      List<Double> all = new ArrayList<>();
      double coldMs;
      try (JsonRpcStdioClient client =
          new JsonRpcStdioClient(launcher.launch(fixture, work, store, env), timeout)) {
        long started = System.nanoTime();
        client.start();
        coldMs = (System.nanoTime() - started) / 1e6;
        for (int round = 0; round < warmupRounds + measuredRounds; round++) {
          for (FixtureSpec.Call c : calls) {
            long t = System.nanoTime();
            JsonNode response = client.callTool(c.tool(), c.args());
            double ms = (System.nanoTime() - t) / 1e6;
            if (response == null) throw new IllegalStateException("no response for " + c.name());
            if (round >= warmupRounds) {
              byTool.computeIfAbsent(c.tool(), k -> new ArrayList<>()).add(ms);
              all.add(ms);
            }
          }
        }
        client.stop();
      }
      Map<String, Percentiles> stats = new LinkedHashMap<>();
      byTool.forEach((tool, ms) -> stats.put(tool, Percentiles.of(ms)));
      return new Result(
          launcher.implementationId(),
          coldMs,
          warmupRounds,
          measuredRounds,
          calls.size(),
          stats,
          Percentiles.of(all));
    } finally {
      try (Stream<Path> walk = Files.walk(work)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
      }
    }
  }
}
