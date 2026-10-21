package io.scos.collection.internal;

/**
 * 스냅샷 축소 보호 기준(ADR-0028). complete_snapshot 정리가 정리 직전 active의 너무 많은 부분을 지우려 하면 정리하지 않는다 — 공급자 장애·권한
 * 변경으로 빈 목록이나 크게 줄어든 목록이 200으로 와도 그 소스의 검색 데이터가 한꺼번에 사라지지 않게. TS에는 없는 의도한 차이다.
 *
 * @param minActive 비율 기준을 적용하는 정리 직전 active 수의 하한. 이보다 작은 소스는 비율로 보호하지 않는다.
 * @param maxDeletePercent 한 번에 지울 수 있는 비율 상한(%). 넘으면 보호한다 — 같으면 정리한다.
 */
public record SnapshotShrinkLimits(int minActive, int maxDeletePercent) {
  /** 데몬 기본값(ADR-0028 §기본값). */
  public static final SnapshotShrinkLimits DEFAULTS = new SnapshotShrinkLimits(10, 50);

  public SnapshotShrinkLimits {
    if (minActive < 1) throw new IllegalArgumentException("minActive must be at least 1");
    if (maxDeletePercent < 1 || maxDeletePercent > 100)
      throw new IllegalArgumentException("maxDeletePercent must be between 1 and 100");
  }

  /**
   * 정리를 건너뛰어야 하는가. active가 하나라도 있는데 전부 관측되지 않았으면(빈 목록) 규모와 무관하게 보호한다. 그 밖에는 active가 minActive 이상이고
   * 지울 비율이 maxDeletePercent를 넘을 때만 보호한다.
   *
   * @param active 정리 직전 active 수(이번 run의 upsert 포함)
   * @param unobserved 그중 이번 run이 관측하지 못한 수(정리 대상)
   */
  public boolean protects(int active, int unobserved) {
    if (unobserved == 0) return false;
    if (unobserved >= active) return true;
    return active >= minActive && (long) unobserved * 100 > (long) maxDeletePercent * active;
  }
}
