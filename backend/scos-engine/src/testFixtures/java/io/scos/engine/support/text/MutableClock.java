package io.scos.engine.support.text;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트가 시각을 전진시키는 Clock — 만료를 기다리지 않고 만료 상태를 만든다. heartbeat·비동기 스레드가 읽으므로 volatile(TESTING §7). */
public final class MutableClock extends Clock {
  private volatile Instant now;

  public MutableClock(Instant start) {
    this.now = start;
  }

  public void advance(Duration d) {
    now = now.plus(d);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
