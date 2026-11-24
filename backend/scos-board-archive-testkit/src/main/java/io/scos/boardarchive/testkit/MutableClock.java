package io.scos.boardarchive.testkit;

import java.time.*;

public final class MutableClock extends Clock {
  private Instant at = Instant.parse("2026-11-18T00:00:00Z");

  public void advance(Duration d) {
    at = at.plus(d);
  }

  @Override
  public Instant instant() {
    return at;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return Clock.fixed(at, zone);
  }
}
