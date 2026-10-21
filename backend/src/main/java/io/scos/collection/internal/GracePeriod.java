package io.scos.collection.internal;

import java.time.Duration;
import java.time.Instant;

/** 유지보수 유예(F9 blob GC·F10 변경 로그 보존): 0일 이상. 이보다 오래된 것만 지운다. */
public record GracePeriod(int days) {
  /** 기본 30일 — 명령 `maintenance`와 데몬 설정(@DefaultValue)이 같은 값을 쓴다. */
  public static final String DEFAULT_DAYS = "30";

  public static final GracePeriod DEFAULT = parseDays(DEFAULT_DAYS);

  public GracePeriod {
    if (days < 0) throw new IllegalArgumentException("grace period must be >= 0 days: " + days);
  }

  public static GracePeriod ofDays(int days) {
    return new GracePeriod(days);
  }

  /** 명령 인자 `--grace-days <n>`: 음이 아닌 정수만. */
  public static GracePeriod parseDays(String raw) {
    try {
      int days = Integer.parseInt(raw);
      if (days >= 0) return new GracePeriod(days);
    } catch (NumberFormatException e) {
      // 아래에서 같은 메시지로 거부
    }
    throw new IllegalArgumentException("--grace-days must be a non-negative integer: " + raw);
  }

  /** now − 일수. */
  public Instant cutoff(Instant now) {
    return now.minus(Duration.ofDays(days));
  }
}
