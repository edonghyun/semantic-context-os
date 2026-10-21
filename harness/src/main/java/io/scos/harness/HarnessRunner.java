package io.scos.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.scos.harness.cassette.MissLog;
import io.scos.harness.compare.ResponseComparator;
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
 * 한 구현에 대해 fixture 호출 전부를 실행한다. 작업 디렉터리(저장소·미스 로그·stderr)를 만들고 끝나면 지운다. record: 응답을
 * expected/<impl>/<name>.json으로 저장. verify: expected/ts와 비교. 두 모드 모두 **카세트 미스 > 0이면 실패**.
 */
public final class HarnessRunner {
  public static final class CassetteMissFailure extends RuntimeException {
    public final int misses;

    public CassetteMissFailure(int misses, String detail) {
      super("cassette misses: " + misses + " — " + detail);
      this.misses = misses;
    }
  }

  public record CallOutcome(String name, JsonNode response, int missesDuringCall) {}

  public record RunResult(
      Map<String, CallOutcome> outcomes,
      int totalMisses,
      JsonRpcStdioClient.Shutdown shutdown,
      Path workDirAfterCleanup) {}

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private final FixtureSpec fixture;
  private final ServerLauncher launcher;
  private final Duration timeout;

  public HarnessRunner(FixtureSpec fixture, ServerLauncher launcher, Duration timeout) {
    this.fixture = fixture;
    this.launcher = launcher;
    this.timeout = timeout;
  }

  /** 기동 → 모든 호출 → 종료. restartMidway=true면 절반 지점에서 정상 종료 후 같은 저장소로 재기동한다(재시작 뒤 응답 동일성 검증용). */
  public RunResult run(boolean restartMidway) throws IOException {
    Path work = Files.createTempDirectory("scos-harness-" + launcher.implementationId() + "-");
    try {
      Path store = launcher.prepare(fixture, work);
      Path missPath = work.resolve("cassette-misses.jsonl");
      MissLog missLog = new MissLog(missPath);
      ServerLauncher.HarnessEnv env =
          new ServerLauncher.HarnessEnv(
              fixture.nowIso(), fixture.cassettePath(), missPath, fixture.spaceId());
      Map<String, CallOutcome> outcomes = new LinkedHashMap<>();
      List<FixtureSpec.Call> calls = fixture.calls();
      JsonRpcStdioClient client =
          new JsonRpcStdioClient(launcher.launch(fixture, work, store, env), timeout);
      JsonRpcStdioClient.Shutdown last;
      try {
        client.start();
        int seen = 0;
        for (int i = 0; i < calls.size(); i++) {
          if (restartMidway && i == calls.size() / 2) {
            JsonRpcStdioClient.Shutdown s = client.stop();
            if (s.forced() || s.exitCode() == null || s.exitCode() != 0)
              throw new IllegalStateException("graceful stop failed before restart: " + s);
            client.start();
          }
          FixtureSpec.Call c = calls.get(i);
          JsonNode response = client.callTool(c.tool(), c.args());
          int now = missLog.count();
          outcomes.put(c.name(), new CallOutcome(c.name(), response, now - seen));
          seen = now;
        }
      } finally {
        last = client.stop();
        client.close();
      }
      int misses = missLog.count();
      if (misses > 0) {
        // 미스 본문(텍스트/프롬프트)은 카세트를 채우는 입력이다(make-p1-fixture.ts cassette <misslog>) — 작업 디렉터리는 지우므로 사본을
        // 남기고 경로를 알린다
        Path kept =
            Files.createTempFile(
                "scos-harness-misses-" + launcher.implementationId() + "-", ".jsonl");
        Files.copy(missPath, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String detail =
            outcomes.values().stream()
                    .filter(o -> o.missesDuringCall() > 0)
                    .map(o -> o.name() + "×" + o.missesDuringCall())
                    .toList()
                + " miss log kept at "
                + kept;
        throw new CassetteMissFailure(misses, detail);
      }
      return new RunResult(outcomes, misses, last, work);
    } finally {
      deleteRecursively(work);
    }
  }

  public void record(RunResult result) throws IOException {
    Path dir = fixture.expectedDir(launcher.implementationId());
    Files.createDirectories(dir);
    for (CallOutcome o : result.outcomes().values())
      Files.writeString(
          dir.resolve(o.name() + ".json"), MAPPER.writeValueAsString(o.response()) + "\n");
  }

  /** expected/<baseline>와 비교. 차이는 호출 이름 → 차이 목록. 기대 파일이 없는 호출도 차이다. */
  public Map<String, List<ResponseComparator.Difference>> verify(
      RunResult result, String baselineId, ResponseComparator comparator, String currentPhase)
      throws IOException {
    return verifyDetailed(result, baselineId, comparator, currentPhase).failures();
  }

  /**
   * 호출별 판정: pass / intentional(영구 의도 차이) / known(기한부 알려진 실패, 리뷰 I-3 allowlist) / fail(목록 밖). 표시된
   * 호출이 같아지면 unexpectedlyEqual(낡은 표시). compare 정책 호출도 표준 비교를 병행해 그것으로도 같으면 unexpectedlyEqual(낡은 정책,
   * N-B). 통과한 호출은 전부 잠긴다 — 내일 깨지면 fail이다.
   */
  public record Verification(
      Map<String, List<ResponseComparator.Difference>> failures,
      Map<String, List<ResponseComparator.Difference>> intentional,
      Map<String, List<ResponseComparator.Difference>> known,
      List<String> passed,
      List<String> unexpectedlyEqual) {}

  /** 계획 6절 단계 순서 — knownFailure.until은 "그 단계가 끝날 때까지" 유효하고, 현재 단계가 그 뒤면 만료(실패)다(재검토 N-2). */
  public static final List<String> PHASES = List.of("P2", "P3", "P4", "P5", "P6", "P7");

  public static boolean expired(String until, String currentPhase) {
    int u = PHASES.indexOf(until), c = PHASES.indexOf(currentPhase);
    if (u < 0 || c < 0)
      throw new IllegalArgumentException(
          "unknown phase: until=" + until + " current=" + currentPhase);
    return c > u;
  }

  public Verification verifyDetailed(
      RunResult result, String baselineId, ResponseComparator comparator, String currentPhase)
      throws IOException {
    Path dir = fixture.expectedDir(baselineId);
    Map<String, java.util.Optional<String>> intended = new LinkedHashMap<>();
    Map<String, java.util.Optional<FixtureSpec.KnownFailure>> knownFailures = new LinkedHashMap<>();
    Map<String, java.util.Optional<FixtureSpec.ComparePolicy>> policies = new LinkedHashMap<>();
    for (FixtureSpec.Call c : fixture.calls()) {
      intended.put(c.name(), c.intentionalDifference());
      knownFailures.put(c.name(), c.knownFailure());
      policies.put(c.name(), c.compare());
    }
    Map<String, List<ResponseComparator.Difference>> failures = new LinkedHashMap<>();
    Map<String, List<ResponseComparator.Difference>> intentional = new LinkedHashMap<>();
    Map<String, List<ResponseComparator.Difference>> known = new LinkedHashMap<>();
    List<String> passed = new ArrayList<>();
    List<String> unexpectedlyEqual = new ArrayList<>();
    for (CallOutcome o : result.outcomes().values()) {
      Path f = dir.resolve(o.name() + ".json");
      java.util.Optional<FixtureSpec.ComparePolicy> policy =
          policies.getOrDefault(o.name(), java.util.Optional.empty());
      List<ResponseComparator.Difference> d;
      boolean stalePolicy = false;
      if (!Files.exists(f))
        d = List.of(new ResponseComparator.Difference("$", "no expected file " + f.getFileName()));
      else {
        JsonNode expected = MAPPER.readTree(Files.readString(f));
        List<ResponseComparator.Difference> standard =
            comparator.compareToolResult(expected, o.response());
        if (policy.isPresent()) {
          d = comparator.compareWithPolicy(expected, o.response(), policy.get());
          stalePolicy = standard.isEmpty(); // 표준 비교로도 같다 — 정책이 낡았다(재검토 N-B)
        } else d = standard;
      }
      boolean intendedDiff =
          intended.getOrDefault(o.name(), java.util.Optional.empty()).isPresent();
      java.util.Optional<FixtureSpec.KnownFailure> kf =
          knownFailures.getOrDefault(o.name(), java.util.Optional.empty());
      boolean knownDiff =
          kf.isPresent() && !expired(kf.get().until(), currentPhase); // 만료된 알려진 실패는 실패다
      if (d.isEmpty()) {
        if (intendedDiff || knownDiff || stalePolicy)
          unexpectedlyEqual.add(o.name()); // 표시했는데 같다 — 표시(정책)가 낡았다는 신호
        passed.add(o.name());
      } else if (intendedDiff) intentional.put(o.name(), d);
      else if (knownDiff) known.put(o.name(), d);
      else failures.put(o.name(), d);
    }
    return new Verification(failures, intentional, known, passed, unexpectedlyEqual);
  }

  static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }

  public static List<String> names(RunResult r) {
    return new ArrayList<>(r.outcomes().keySet());
  }
}
