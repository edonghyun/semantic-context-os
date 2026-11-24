package io.scos.boardarchive.crawl;

import java.time.Duration;
import java.util.Optional;

public record CrawlSettings(
    Duration recheckWindow,
    Duration noSignalRecheckEvery,
    int sweepEveryRuns,
    Optional<Duration> initialDepth,
    Duration timeBudget) {
  public CrawlSettings {
    if (recheckWindow.isNegative()
        || !noSignalRecheckEvery.isPositive()
        || sweepEveryRuns < 1
        || !timeBudget.isPositive()
        || initialDepth.filter(Duration::isNegative).isPresent())
      throw new IllegalArgumentException("Invalid crawl settings");
  }

  public static CrawlSettings defaults(Duration timeBudget) {
    return new CrawlSettings(
        Duration.ofDays(14), Duration.ofDays(7), 10, Optional.empty(), timeBudget);
  }
}
