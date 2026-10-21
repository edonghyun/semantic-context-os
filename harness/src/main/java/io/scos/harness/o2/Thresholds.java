package io.scos.harness.o2;

import java.util.Optional;

/**
 * 판정 임계값(T11.1 기준). 주지 않은 것은 판정하지 않는다. 호출 오류는 임계값과 무관하게 "실행 오류"로 따로 센다(종료 코드 3).
 *
 * @param minJaccard 질의마다 Jaccard@k가 이보다 작으면 위반
 * @param minMeanJaccard 비교된 질의의 Jaccard@k 평균이 이보다 작으면 위반
 * @param minRbo 질의마다 RBO가 이보다 작으면 위반
 * @param maxFirstHitRegressions 정답 있는 질의 중 B(후보)의 첫 적중이 A(기준)보다 나쁜 질의 수가 이보다 많으면 위반. B가 더 나은 질의는 세지
 *     않는다. 정답 없는 질의(A 1위 기준)는 세지 않는다
 * @param maxP95Ratio B p95 / A p95가 이보다 크면 위반
 * @param maxP95Ms B p95(ms)가 이보다 크면 위반
 * @param lowOverlapBelow 질의 Jaccard@k가 이보다 작으면 "원인 분류 대상"으로 따로 뽑는다(위반 아님)
 */
public record Thresholds(
    Optional<Double> minJaccard,
    Optional<Double> minMeanJaccard,
    Optional<Double> minRbo,
    Optional<Integer> maxFirstHitRegressions,
    Optional<Double> maxP95Ratio,
    Optional<Double> maxP95Ms,
    Optional<Double> lowOverlapBelow) {
  public static final Thresholds NONE =
      new Thresholds(
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());

  /** 판정 임계값(분류 목록 기준은 판정이 아니다)을 하나라도 줬는가. */
  public boolean any() {
    return minJaccard.isPresent()
        || minMeanJaccard.isPresent()
        || minRbo.isPresent()
        || maxFirstHitRegressions.isPresent()
        || maxP95Ratio.isPresent()
        || maxP95Ms.isPresent();
  }
}
