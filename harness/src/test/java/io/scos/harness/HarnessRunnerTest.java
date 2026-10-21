package io.scos.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scos.harness.compare.ResponseComparator;
import io.scos.harness.fixture.FixtureSpec;
import io.scos.harness.mcp.JsonRpcStdioClient;
import io.scos.harness.testing.FakeServerLauncher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 러너 규칙: 미스 카운터가 응답 내용보다 우선한다, 작업 디렉터리는 지운다, 재시작 뒤에도 호출 전부 수행, 낡은 비교 정책 감지. */
@DisplayName("하네스 러너는 미스를 응답보다 먼저 보고 호출마다 판정한다(ADR-0016)")
class HarnessRunnerTest {
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);

  /** compare 정책을 가진 호출(표준 비교로는 TS·Java가 다르다). */
  static final String POLICY_CALL = "search-dup-embedding";

  static final String NORMAL_LOOKING_RESPONSE =
      "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"hits\\\":[{\\\"documentId\\\":\\\"a\\\",\\\"score\\\":1}]}\"}]}";

  final FixtureSpec fixture = fixture();
  final ResponseComparator comparator =
      new ResponseComparator(ResponseComparator.Policy.standard());

  static FixtureSpec fixture() {
    try {
      return FixtureSpec.load(Path.of(System.getProperty("harness.root", "."), "fixtures", "p1"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  HarnessRunner runner(Map<String, String> fakeEnv) {
    return new HarnessRunner(fixture, new FakeServerLauncher(fakeEnv), CALL_TIMEOUT);
  }

  @Test
  @DisplayName("미스 로그에 한 줄이라도 있으면 응답이 정상이어도 CassetteMissFailure다")
  void missCounterBeatsNormalLookingResponse() {
    // given: fallback이 미스를 숨기지 못한다
    HarnessRunner r =
        runner(Map.of("FAKE_MISS_LOG", "set", "FAKE_RESPONSE", NORMAL_LOOKING_RESPONSE));

    // when·then
    assertThatThrownBy(() -> r.run(false))
        .isInstanceOf(HarnessRunner.CassetteMissFailure.class)
        .hasMessageContaining("cassette misses: " + fixture.calls().size());
  }

  @Nested
  @DisplayName("미스 없이 중간 재시작을 넣어 돌리면")
  class CleanRunWithRestart {
    HarnessRunner.RunResult runWithRestart() throws IOException {
      return runner(Map.of()).run(true);
    }

    @Test
    @DisplayName("모든 호출의 응답을 모은다")
    void collectsAllCalls() throws Exception {
      // when
      HarnessRunner.RunResult run = runWithRestart();

      // then
      assertThat(run.outcomes()).hasSize(fixture.calls().size());
    }

    @Test
    @DisplayName("카세트 미스가 없다")
    void hasNoCassetteMisses() throws Exception {
      // when
      HarnessRunner.RunResult res = runWithRestart();

      // then
      assertThat(res.totalMisses()).isZero();
    }

    @Test
    @DisplayName("서버는 강제 종료 없이 끝난다")
    void stopsGracefully() throws Exception {
      // when
      HarnessRunner.RunResult res = runWithRestart();

      // then
      assertThat(res.shutdown().forced()).isFalse();
    }

    @Test
    @DisplayName("작업 디렉터리를 지운다")
    void cleansWorkDirectory() throws Exception {
      // when
      HarnessRunner.RunResult run = runWithRestart();

      // then
      assertThat(run.workDirAfterCleanup()).doesNotExist();
    }
  }

  @ParameterizedTest(name = "until {0}, 현재 {1} → expired {2}")
  @CsvSource({"P3, P2, false", "P3, P3, false", "P3, P4, true"})
  @DisplayName("knownFailure.until은 단계 순서로 만료된다(현재 단계가 until 뒤면 실패로 승격)")
  void knownFailureExpiry(String until, String current, boolean expired) {
    // when·then
    assertThat(HarnessRunner.expired(until, current)).isEqualTo(expired);
  }

  @Test
  @DisplayName("알 수 없는 단계 이름은 IllegalArgumentException이다")
  void unknownPhaseIsRejected() {
    // when·then
    assertThatThrownBy(() -> HarnessRunner.expired("P9", "P3"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** compare 정책 호출(POLICY_CALL)의 응답을 recordedBy가 녹화한 기대 파일로 두고 TS 기준으로 판정한다. 표준 비교도 병행한다. */
  abstract class PolicyCallSituation {
    HarnessRunner.RunResult recorded;

    void givenPolicyCallRecordedBy(String recordedBy) throws IOException {
      boolean hasPolicy =
          fixture.calls().stream()
              .filter(c -> c.name().equals(POLICY_CALL))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("given 준비 실패: fixture에 " + POLICY_CALL + "가 없다"))
              .compare()
              .isPresent();
      if (!hasPolicy)
        throw new IllegalStateException("given 준비 실패: " + POLICY_CALL + "에 compare 정책이 없다");
      recorded = result(fixture.expectedDir(recordedBy).resolve(POLICY_CALL + ".json"));
    }

    HarnessRunner.Verification verify() throws IOException {
      return runner(Map.of()).verifyDetailed(recorded, "ts", comparator, "P3");
    }
  }

  @Nested
  @DisplayName("정책 호출의 응답이 표준 비교로도 TS와 같으면")
  class PolicyCallSameAsTs extends PolicyCallSituation {
    @BeforeEach
    void givenSame() throws IOException {
      givenPolicyCallRecordedBy("ts");
    }

    @Test
    @DisplayName("통과한다")
    void sameResponsePasses() throws Exception {
      // when
      HarnessRunner.Verification same = verify();

      // then
      assertThat(same.passed()).containsExactly(POLICY_CALL);
    }

    @Test
    @DisplayName("낡은 정책(unexpectedlyEqual)으로 표시한다")
    void sameResponseMarksStalePolicy() throws Exception {
      // when
      HarnessRunner.Verification same = verify();

      // then
      assertThat(same.unexpectedlyEqual()).containsExactly(POLICY_CALL);
    }
  }

  @Nested
  @DisplayName("정책 호출의 응답이 정책 안에서만 TS와 다르면")
  class PolicyCallDiffersWithinPolicy extends PolicyCallSituation {
    @BeforeEach
    void givenWithinPolicy() throws IOException {
      givenPolicyCallRecordedBy("java");
    }

    @Test
    @DisplayName("통과한다")
    void differenceWithinPolicyPasses() throws Exception {
      // when
      HarnessRunner.Verification within = verify();

      // then
      assertThat(within.passed()).containsExactly(POLICY_CALL);
    }

    @Test
    @DisplayName("실패로 세지 않는다")
    void differenceWithinPolicyIsNoFailure() throws Exception {
      // when
      HarnessRunner.Verification within = verify();

      // then
      assertThat(within.failures()).isEmpty();
    }

    @Test
    @DisplayName("낡은 정책으로 표시하지 않는다")
    void differenceWithinPolicyIsNotStale() throws Exception {
      // when
      HarnessRunner.Verification within = verify();

      // then
      assertThat(within.unexpectedlyEqual()).isEmpty();
    }
  }

  /**
   * 호출별 판정 표: 같음 → 통과, 의도 차이 표시 → intentional, 기한 안 알려진 실패 → known, 기한 지난 알려진 실패·표시 없는 차이·기대 파일 없음
   * → 실패, 표시했는데 같음 → 통과이면서 낡은 표시. 합성 fixture(@TempDir)로 본다.
   */
  @Nested
  @DisplayName("같음·차이·표시가 섞인 합성 실행 결과를 판정하면")
  class Classification {
    static final String SAME = "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"hits\\\":[]}\"}]}";
    static final String DIFFERENT =
        "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"hits\\\":[{\\\"documentId\\\":\\\"x\\\",\\\"score\\\":1}]}\"}]}";

    @TempDir Path dir;
    HarnessRunner runner;
    HarnessRunner.RunResult run;

    @BeforeEach
    void givenSyntheticFixture() throws IOException {
      Files.writeString(
          dir.resolve("documents.json"),
          "{\"version\":1,\"now\":\"2026-10-13T00:00:00.000Z\",\"units\":[],\"embedding\":{\"dim\":2}}");
      Files.writeString(
          dir.resolve("calls.json"),
          """
          {"version":1,"now":"2026-10-13T00:00:00.000Z","spaceId":"legacy","calls":[
            {"name":"same","tool":"search_context","args":{}},
            {"name":"unmarked","tool":"search_context","args":{}},
            {"name":"missing","tool":"search_context","args":{}},
            {"name":"intended","tool":"search_context","args":{},"intentionalDifference":"사용자 결정"},
            {"name":"intended-equal","tool":"search_context","args":{},"intentionalDifference":"사용자 결정"},
            {"name":"known","tool":"search_context","args":{},"knownFailure":{"reason":"P3에서 고친다","until":"P3"}},
            {"name":"known-equal","tool":"search_context","args":{},"knownFailure":{"reason":"P3에서 고친다","until":"P3"}}
          ]}
          """);
      Path expected = Files.createDirectories(dir.resolve("expected").resolve("ts"));
      for (String name :
          List.of("same", "unmarked", "intended", "intended-equal", "known", "known-equal"))
        Files.writeString(expected.resolve(name + ".json"), SAME);
      FixtureSpec synthetic = FixtureSpec.load(dir);
      runner = new HarnessRunner(synthetic, new FakeServerLauncher(Map.of()), CALL_TIMEOUT);
      ObjectMapper json = new ObjectMapper();
      Map<String, HarnessRunner.CallOutcome> outcomes = new LinkedHashMap<>();
      for (var e :
          Map.of(
                  "same", SAME,
                  "unmarked", DIFFERENT,
                  "missing", SAME,
                  "intended", DIFFERENT,
                  "intended-equal", SAME,
                  "known", DIFFERENT,
                  "known-equal", SAME)
              .entrySet())
        outcomes.put(
            e.getKey(), new HarnessRunner.CallOutcome(e.getKey(), json.readTree(e.getValue()), 0));
      run =
          new HarnessRunner.RunResult(
              outcomes, 0, new JsonRpcStdioClient.Shutdown(0, false), Path.of("unused"));
    }

    @Test
    @DisplayName("현재 단계가 기한 안이면 같은 호출은 통과한다")
    void sameAndMarkedEqualPass() throws IOException {
      // when
      var v = runner.verifyDetailed(run, "ts", comparator, "P3");

      // then
      assertThat(v.passed()).containsExactlyInAnyOrder("same", "intended-equal", "known-equal");
    }

    @Test
    @DisplayName("표시 없는 차이와 기대 파일 없음은 실패다")
    void unmarkedAndMissingFail() throws IOException {
      // when
      var v = runner.verifyDetailed(run, "ts", comparator, "P3");

      // then
      assertThat(v.failures()).containsOnlyKeys("unmarked", "missing");
    }

    @Test
    @DisplayName("의도 차이로 표시한 차이는 intentional이다")
    void markedDifferenceIsIntentional() throws IOException {
      // when
      var v = runner.verifyDetailed(run, "ts", comparator, "P3");

      // then
      assertThat(v.intentional()).containsOnlyKeys("intended");
    }

    @Test
    @DisplayName("기한 안의 알려진 실패는 known이다")
    void knownWithinPhase() throws IOException {
      // when
      var v = runner.verifyDetailed(run, "ts", comparator, "P3");

      // then
      assertThat(v.known()).containsOnlyKeys("known");
    }

    @Test
    @DisplayName("의도 차이로 표시했는데 같아진 호출은 낡은 표시다")
    void intendedButEqualIsStale() throws IOException {
      // when
      var v = runner.verifyDetailed(run, "ts", comparator, "P3");

      // then
      assertThat(v.unexpectedlyEqual()).contains("intended-equal");
    }

    @Test
    @DisplayName("알려진 실패가 이제 같아지면 낡은 표시다")
    void knownButEqualIsStale() throws IOException {
      // when
      var v = runner.verifyDetailed(run, "ts", comparator, "P3");

      // then
      assertThat(v.unexpectedlyEqual()).contains("known-equal");
    }

    @Test
    @DisplayName("기한(P3)이 지난 단계(P4)에서는 알려진 실패도 실패로 승격된다")
    void expiredKnownFailureFails() throws IOException {
      // when
      var v = runner.verifyDetailed(run, "ts", comparator, "P4");

      // then
      assertThat(v.failures()).containsOnlyKeys("unmarked", "missing", "known");
    }
  }

  private static HarnessRunner.RunResult result(Path response) throws IOException {
    var node = new ObjectMapper().readTree(Files.readString(response));
    return new HarnessRunner.RunResult(
        Map.of(POLICY_CALL, new HarnessRunner.CallOutcome(POLICY_CALL, node, 0)),
        0,
        new JsonRpcStdioClient.Shutdown(0, false),
        Path.of("unused"));
  }
}
