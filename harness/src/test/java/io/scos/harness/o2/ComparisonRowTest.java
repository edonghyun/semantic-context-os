package io.scos.harness.o2;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.harness.o2.ParallelComparison.FirstHitBasis;
import io.scos.harness.o2.ParallelComparison.Measured;
import io.scos.harness.o2.ParallelComparison.Options;
import io.scos.harness.o2.ParallelComparison.Row;
import io.scos.harness.o2.ParallelComparison.Summary;
import io.scos.harness.o2.ParallelComparison.Verdict;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 질의 한 줄의 지표·첫 적중·분류와 요약·판정(프로세스 없이). 임계값은 T11.1 확정 기준의 모양(평균 Jaccard 0.8, 질의별 회귀 0, p95 비 2·2초, 분류
 * 0.5)을 쓴다.
 */
@DisplayName("비교 행은 지표·첫 적중·분류를 내고 요약은 기준으로 판정한다(T11.1)")
class ComparisonRowTest {
  static final int LIMIT = 5;
  static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final double A_MS = 10;
  static final double B_MS = 30;
  static final ComparisonQuery FROM_LOG = new ComparisonQuery("q", Optional.empty());
  static final ComparisonQuery FROM_PACK = new ComparisonQuery("q", Optional.of(List.of("d3")));
  static final Thresholds T11_1 =
      new Thresholds(
          Optional.empty(),
          Optional.of(0.8),
          Optional.empty(),
          Optional.of(0),
          Optional.of(2.0),
          Optional.of(2000.0),
          Optional.of(0.5));

  static ParallelComparison comparing(Thresholds thresholds) {
    return new ParallelComparison(
        new Options(LIMIT, LIMIT, RankingMetrics.DEFAULT_RBO_P, thresholds, TIMEOUT, 0, 1, false));
  }

  static Measured measured(double ms, String... ids) {
    return new Measured(new SearchHits(List.of(ids), Optional.empty()), ms);
  }

  static Measured failed() {
    return new Measured(SearchHits.failed("call failed: IOException"), B_MS);
  }

  static Row row(ComparisonQuery q, Measured a, Measured b) {
    return comparing(T11_1).row(0, q, a, b);
  }

  static Summary summary(Thresholds t, List<Measured[]> pairs, ComparisonQuery q) {
    var c = comparing(t);
    return c.summarize(pairs.stream().map(p -> c.row(0, q, p[0], p[1])).toList());
  }

  @Test
  @DisplayName("기본 보고는 질의 원문 없이 해시만 담는다")
  void hashOnlyByDefault() {
    // when
    Row r = row(FROM_LOG, measured(A_MS, "d1"), measured(B_MS, "d1"));

    // then
    assertThat(r.query()).isEmpty();
  }

  @Nested
  @DisplayName("정답 없는 질의(질의 로그)의 첫 적중은")
  class ATop1Basis {
    Row compare() {
      return row(FROM_LOG, measured(A_MS, "d1", "d2"), measured(B_MS, "d2", "d1"));
    }

    @Test
    @DisplayName("A 1위 문서를 기준으로 한다")
    void basis() {
      // when
      Row r = compare();

      // then
      assertThat(r.firstHitBasis()).isEqualTo(FirstHitBasis.A_TOP1);
    }

    @Test
    @DisplayName("B에서 그 문서의 순위와 A(1)의 부호 있는 차를 낸다")
    void signedDelta() {
      // when
      Row r = compare();

      // then
      assertThat(r.firstHitDelta()).contains(1);
    }

    @Test
    @DisplayName("회귀로 세지 않는다(정답 있는 질의만 센다)")
    void notARegression() {
      // when
      Row r = compare();

      // then
      assertThat(r.firstHitRegression()).isFalse();
    }
  }

  @Nested
  @DisplayName("정답 있는 질의(pack)에서")
  class ExpectedBasis {
    @Test
    @DisplayName("B가 더 아래에서 적중하면 회귀다")
    void lowerIsRegression() {
      // when
      Row r = row(FROM_PACK, measured(A_MS, "d3"), measured(B_MS, "d1", "d2", "d3"));

      // then
      assertThat(r.firstHitRegression()).isTrue();
    }

    @Test
    @DisplayName("B가 더 위에서 적중하면 회귀가 아니다")
    void higherIsNotRegression() {
      // when
      Row r = row(FROM_PACK, measured(A_MS, "d1", "d3"), measured(B_MS, "d3"));

      // then
      assertThat(r.firstHitRegression()).isFalse();
    }

    @Test
    @DisplayName("B가 더 위에서 적중하면 차는 음수다")
    void higherHasNegativeDelta() {
      // when
      Row r = row(FROM_PACK, measured(A_MS, "d1", "d3"), measured(B_MS, "d3"));

      // then
      assertThat(r.firstHitDelta()).contains(-1);
    }

    @Test
    @DisplayName("A만 적중하면 회귀다")
    void onlyAIsRegression() {
      // when
      Row r = row(FROM_PACK, measured(A_MS, "d3"), measured(B_MS, "d1"));

      // then
      assertThat(r.firstHitRegression()).isTrue();
    }

    @Test
    @DisplayName("B만 적중하면 회귀가 아니다")
    void onlyBIsNotRegression() {
      // when
      Row r = row(FROM_PACK, measured(A_MS, "d1"), measured(B_MS, "d3"));

      // then
      assertThat(r.firstHitRegression()).isFalse();
    }
  }

  @Nested
  @DisplayName("겹침이 분류 기준보다 낮으면")
  class LowOverlap {
    Row compare() {
      return row(FROM_LOG, measured(A_MS, "d1", "d2"), measured(B_MS, "d1", "d9"));
    }

    @Test
    @DisplayName("분류 대상이다")
    void lowOverlapIsListed() {
      // when
      Row r = compare();

      // then
      assertThat(r.lowOverlap()).isTrue();
    }

    @Test
    @DisplayName("위반이 아니다")
    void lowOverlapIsNotViolation() {
      // when
      Row r = compare();

      // then
      assertThat(r.violations()).isEmpty();
    }
  }

  @Nested
  @DisplayName("질의 행들을 요약해 판정하면")
  class Verdicts {
    @Test
    @DisplayName("평균 Jaccard가 정확히 하한이면 통과다")
    void meanAtBoundaryPasses() {
      // when: Jaccard 4/5 = 0.8, 지연은 B/A = 1(비율·절대 상한 안)
      var s =
          summary(
              T11_1,
              List.<Measured[]>of(
                  new Measured[] {
                    measured(A_MS, "a", "b", "c", "d"), measured(A_MS, "a", "b", "c", "d", "e")
                  }),
              FROM_LOG);

      // then
      assertThat(s.verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    @DisplayName("한 질의가 0.5여도 평균이 하한 이상이면 통과다")
    void meanNotMinimumDecides() {
      // given: Jaccard 1·1·1·1/2 → 평균 0.875(최솟값 0.5는 하한 0.8 아래)
      Measured[] same = {measured(A_MS, "a", "b"), measured(A_MS, "a", "b")};
      Measured[] half = {measured(A_MS, "a", "b"), measured(A_MS, "a")};

      // when
      var s = summary(T11_1, List.of(same, same, same, half), FROM_LOG);

      // then
      assertThat(s.verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    @DisplayName("평균 Jaccard가 하한보다 낮으면 위반이다")
    void meanBelowViolates() {
      // when
      var s =
          summary(
              T11_1,
              List.<Measured[]>of(
                  new Measured[] {measured(A_MS, "d1", "d2"), measured(A_MS, "d1", "d9")}),
              FROM_LOG);

      // then
      assertThat(s.violations()).containsEntry("meanJaccard", 1);
    }

    @Test
    @DisplayName("B p95가 절대 상한을 넘으면 위반이다")
    void p95AbsoluteCap() {
      // when
      var s =
          summary(
              T11_1,
              List.<Measured[]>of(new Measured[] {measured(1500, "d1"), measured(2500, "d1")}),
              FROM_LOG);

      // then
      assertThat(s.violations()).containsEntry("p95Ms", 1);
    }

    @Test
    @DisplayName("B p95가 A의 2배를 넘으면 위반이다")
    void p95Ratio() {
      // when
      var s =
          summary(
              T11_1,
              List.<Measured[]>of(new Measured[] {measured(A_MS, "d1"), measured(B_MS, "d1")}),
              FROM_LOG);

      // then
      assertThat(s.violations()).containsEntry("p95Ratio", 1);
    }

    @Test
    @DisplayName("회귀가 허용 수를 넘으면 위반이다")
    void regressionsViolate() {
      // when
      var s =
          summary(
              T11_1,
              List.<Measured[]>of(
                  new Measured[] {measured(A_MS, "d3"), measured(A_MS, "d1", "d3")}),
              FROM_PACK);

      // then
      assertThat(s.violations()).containsEntry("firstHitRegressions", 1);
    }

    @Test
    @DisplayName("호출 오류가 있으면 기준과 무관하게 ERRORS다")
    void errorsWin() {
      // when
      var s =
          summary(
              Thresholds.NONE,
              List.<Measured[]>of(new Measured[] {measured(A_MS, "d1"), failed()}),
              FROM_LOG);

      // then
      assertThat(s.verdict()).isEqualTo(Verdict.ERRORS);
    }

    @Test
    @DisplayName("기준이 없고 오류도 없으면 NO_THRESHOLDS다")
    void noThresholds() {
      // when
      var s =
          summary(
              Thresholds.NONE,
              List.<Measured[]>of(new Measured[] {measured(A_MS, "d1"), measured(B_MS, "d1")}),
              FROM_LOG);

      // then
      assertThat(s.verdict()).isEqualTo(Verdict.NO_THRESHOLDS);
    }
  }

  @Nested
  @DisplayName("한쪽 호출이 실패하면")
  class Failed {
    Row compare() {
      return row(FROM_LOG, measured(A_MS, "d1"), failed());
    }

    @Test
    @DisplayName("지표를 내지 않는다")
    void noMetrics() {
      // when
      Row r = compare();

      // then
      assertThat(r.jaccard()).isEmpty();
    }

    @Test
    @DisplayName("error로 남긴다")
    void markedError() {
      // when
      Row r = compare();

      // then
      assertThat(r.violations()).containsExactly("error");
    }
  }

  @Test
  @DisplayName("요약은 1위 일치 비율을 낸다")
  void top1Agreement() {
    // when
    var s =
        summary(
            Thresholds.NONE,
            List.of(
                new Measured[] {measured(A_MS, "d1"), measured(B_MS, "d1")},
                new Measured[] {measured(A_MS, "d1"), measured(B_MS, "d2")}),
            FROM_LOG);

    // then
    assertThat(s.top1Agreement()).contains(0.5);
  }

  @Test
  @DisplayName("지연은 성공한 호출만 센다")
  void latencyFromSuccessfulCallsOnly() {
    // when
    var s =
        summary(
            Thresholds.NONE,
            List.of(
                new Measured[] {measured(A_MS, "d1"), measured(B_MS, "d1")},
                new Measured[] {
                  measured(A_MS, "d1"), new Measured(SearchHits.failed("x"), 99_999)
                }),
            FROM_LOG);

    // then
    assertThat(s.latencyB().orElseThrow().samples()).isEqualTo(1);
  }
}
