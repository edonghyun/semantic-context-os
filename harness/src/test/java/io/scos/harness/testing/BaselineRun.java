package io.scos.harness.testing;

import com.fasterxml.jackson.databind.JsonNode;
import io.scos.harness.HarnessRunner;
import io.scos.harness.compare.ResponseComparator;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기준 실행 기록(TESTING §2.5 규칙 6): 같은 fixture로 한 번 돌리고, 중간 재시작을 넣어 한 번 더 돌린 결과. 기준 테스트의 @BeforeAll이 만들고
 * 계약별 테스트가 읽는다.
 */
public final class BaselineRun {
  public static final ResponseComparator COMPARATOR =
      new ResponseComparator(ResponseComparator.Policy.standard());

  private final HarnessRunner runner;
  private final HarnessRunner.RunResult first;
  private final HarnessRunner.RunResult restarted;

  private BaselineRun(
      HarnessRunner runner, HarnessRunner.RunResult first, HarnessRunner.RunResult restarted) {
    this.runner = runner;
    this.first = first;
    this.restarted = restarted;
  }

  /** 한 번 돌리고, -Dharness.mode=record면 그 결과를 expected로 녹화한 뒤, 재시작을 넣어 한 번 더 돌린다. */
  public static BaselineRun twice(HarnessRunner runner) throws IOException {
    HarnessRunner.RunResult first = runner.run(false);
    if (recording()) runner.record(first);
    return new BaselineRun(runner, first, runner.run(true));
  }

  public static boolean recording() {
    return "record".equals(System.getProperty("harness.mode"));
  }

  public static String phase() {
    return System.getProperty("harness.phase", "P3");
  }

  public HarnessRunner.RunResult first() {
    return first;
  }

  /** 호출별로 재시작 전후 응답의 차이(같으면 빠진다). */
  public Map<String, List<ResponseComparator.Difference>> restartDifferences() {
    Map<String, List<ResponseComparator.Difference>> diffs = new LinkedHashMap<>();
    for (var e : first.outcomes().entrySet()) {
      List<ResponseComparator.Difference> d =
          COMPARATOR.compareToolResult(
              e.getValue().response(), restarted.outcomes().get(e.getKey()).response());
      if (!d.isEmpty()) diffs.put(e.getKey(), d);
    }
    return diffs;
  }

  /** 첫 실행을 커밋된 기대 응답(baselineId)과 호출별로 비교한다. */
  public HarnessRunner.Verification verify(String baselineId) throws IOException {
    return runner.verifyDetailed(first, baselineId, COMPARATOR, phase());
  }

  public JsonNode response(String call) {
    return first.outcomes().get(call).response();
  }

  /** isError 래핑을 벗긴 도구 결과의 첫 content JSON. */
  public JsonNode resultJson(String call) {
    return ResponseComparator.unwrap(response(call)).path("content").get(0).path("json");
  }
}
