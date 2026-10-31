package io.scos.collection.internal;

import java.time.Duration;
import java.time.Instant;

/** 유지보수 유예(F9 blob GC·F10 변경 로그 보존): 0일 이상. 이보다 오래된 것만 지운다. */
public record GracePeriod(int days) {
  /** 기본 30일 — 명령 `maintenance`와 데몬 설정(@DefaultValue)이 같은 값을 쓴다. */
  public static final GracePeriod DEFAULT = new GracePeriod(30);

  /** {@link #DEFAULT}의 일수를 설정 주석(@DefaultValue)이 받는 상수 글자로. */
  public static final String DEFAULT_DAYS = "30";

  public GracePeriod {
    if (days < 0) throw new IllegalArgumentException("grace period must be >= 0 days: " + days);
  }

  public static GracePeriod ofDays(int days) {
    return new GracePeriod(days);
  }

  /** now − 일수. */
  public Instant cutoff(Instant now) {
    return now.minus(Duration.ofDays(days));
  }
}
