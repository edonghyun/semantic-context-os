package io.scos.boardarchive.fetch;

import java.time.Duration;
import java.util.Optional;

public record SitePolicy(
    String userAgent, Duration minInterval, int maxRetries, Optional<Permission> robotsException) {
  public SitePolicy {
    if (userAgent.isBlank()
        || minInterval.compareTo(Duration.ofSeconds(3)) < 0
        || maxRetries < 0
        || maxRetries > 4) throw new IllegalArgumentException("Invalid politeness policy");
  }

  public static SitePolicy defaults(String userAgent) {
    return new SitePolicy(userAgent, Duration.ofSeconds(3), 4, Optional.empty());
  }
}
