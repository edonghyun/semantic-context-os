package io.scos.collection.internal;

import java.util.Optional;

/**
 * 소스별 스냅샷 축소 보호 기준(ADR-0028). 등록(`source_instances`)에 두고, 비어 있는 값은 데몬 기본값({@link
 * SnapshotShrinkLimits})을 따른다 — 소스별 감독 주기({@link SourceSchedule})와 같은 방식이다.
 */
public record SnapshotShrinkOverrides(
    Optional<Integer> minActive, Optional<Integer> maxDeletePercent) {
  /** 소스별 값 없음 — 데몬 기본값을 따른다. */
  public static final SnapshotShrinkOverrides DAEMON_DEFAULTS =
      new SnapshotShrinkOverrides(Optional.empty(), Optional.empty());

  public SnapshotShrinkOverrides {
    // 범위 검증은 기준 하나와 같다(빈 값은 기본값으로 채워 본다)
    new SnapshotShrinkLimits(
        minActive.orElse(SnapshotShrinkLimits.DEFAULTS.minActive()),
        maxDeletePercent.orElse(SnapshotShrinkLimits.DEFAULTS.maxDeletePercent()));
  }

  /** 소스 값, 없으면 기본값. */
  public SnapshotShrinkLimits over(SnapshotShrinkLimits defaults) {
    return new SnapshotShrinkLimits(
        minActive.orElse(defaults.minActive()),
        maxDeletePercent.orElse(defaults.maxDeletePercent()));
  }
}
