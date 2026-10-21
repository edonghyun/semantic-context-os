package io.scos.collection.internal;

import java.time.Duration;
import java.util.Optional;

/**
 * 소스별 감독 주기(ADR-0022 §3). TS는 데몬 옵션 `--interval-seconds-for <id>=<s>`·`snapshotIntervalMsBySource`로
 * 줬고 Java는 등록(`source_instances`)에 둔다. 비어 있으면 데몬 기본값(`SourceSupervisor.Options`)을 쓴다. 값은 1초 이상의 초
 * 단위다(열이 초 단위 INTEGER).
 *
 * @param collectInterval 성공한 수집 뒤 다음 수집까지(TS intervalMsBySource). 실패 뒤 재시도 지연은 바꾸지 않는다.
 * @param snapshotInterval 스냅샷 모드 간격(TS snapshotIntervalMsBySource). 데몬 스냅샷 설정이 없어도 이 소스에는 적용된다.
 */
public record SourceSchedule(
    Optional<Duration> collectInterval, Optional<Duration> snapshotInterval) {
  /** 소스별 값 없음 — 데몬 기본값을 따른다. */
  public static final SourceSchedule DAEMON_DEFAULTS =
      new SourceSchedule(Optional.empty(), Optional.empty());

  public SourceSchedule {
    collectInterval.ifPresent(d -> requireWholePositiveSeconds("collectInterval", d));
    snapshotInterval.ifPresent(d -> requireWholePositiveSeconds("snapshotInterval", d));
  }

  /** 성공 뒤 간격: 소스 값, 없으면 데몬 기본값. */
  public Duration collectIntervalOr(Duration daemonDefault) {
    return collectInterval.orElse(daemonDefault);
  }

  /** 스냅샷 간격: 소스 값, 없으면 데몬 기본값(데몬도 없으면 스냅샷 모드 없음). */
  public Optional<Duration> snapshotIntervalOr(Optional<Duration> daemonDefault) {
    return snapshotInterval.or(() -> daemonDefault);
  }

  private static void requireWholePositiveSeconds(String name, Duration d) {
    if (d.isNegative() || d.isZero() || d.toNanosPart() != 0 || d.toSeconds() > Integer.MAX_VALUE)
      throw new IllegalArgumentException(
          name + " must be a whole number of seconds between 1 and " + Integer.MAX_VALUE);
  }
}
