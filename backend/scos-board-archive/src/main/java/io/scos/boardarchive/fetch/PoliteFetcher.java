package io.scos.boardarchive.fetch;

import io.scos.boardarchive.driver.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/**
 * Serial requests and retries share the same interval, including the robots probe. Use one instance
 * per site across runs. Redirect-following clients are refused: redirected wire requests would
 * bypass both the interval and robots.
 */
public final class PoliteFetcher implements Fetcher {
  private final HttpClient client;
  private final Clock clock;
  private final Sleeper sleeper;
  private final SitePolicy policy;
  private final FetchLog log;
  private final Map<String, CachedRobots> robots = new HashMap<>();

  private record CachedRobots(RobotsRules rules, Instant expiresAt) {}

  private Optional<Instant> lastRequest = Optional.empty();

  public PoliteFetcher(
      HttpClient client, Clock clock, Sleeper sleeper, SitePolicy policy, FetchLog log) {
    if (client.followRedirects() != HttpClient.Redirect.NEVER)
      throw new IllegalArgumentException("Client must not follow redirects");
    this.client = client;
    this.clock = clock;
    this.sleeper = sleeper;
    this.policy = policy;
    this.log = log;
  }

  public FetchLog log() {
    return log;
  }

  @Override
  public synchronized Response get(URI uri) {
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null)
      throw new IllegalArgumentException("Expected public HTTP URI");
    String origin = uri.getScheme() + "://" + uri.getRawAuthority();
    CachedRobots cached = robots.get(origin);
    if (cached == null || !clock.instant().isBefore(cached.expiresAt())) {
      cached = new CachedRobots(loadRobots(origin), clock.instant().plus(Duration.ofDays(1)));
      robots.put(origin, cached);
    }
    RobotsRules rules = cached.rules();
    String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    if (!rules.allows(path)) {
      Permission permission = policy.robotsException().orElseThrow(RobotsDisallowedException::new);
      log.permitted(permission);
    }
    return request(uri);
  }

  private RobotsRules loadRobots(String origin) {
    try {
      Response response = request(URI.create(origin + "/robots.txt"));
      if (response.status() == 404) return RobotsRules.allowAll();
      return response.status() >= 200 && response.status() < 300
          ? RobotsRules.parse(response.body(), policy.userAgent())
          : RobotsRules.denyAll();
    } catch (TransportFailureException e) {
      return RobotsRules.denyAll();
    }
  }

  private Response request(URI uri) {
    for (int attempt = 0; ; attempt++) {
      waitInterval();
      try {
        log.attempted();
        lastRequest = Optional.of(clock.instant());
        HttpRequest req =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", policy.userAgent())
                .GET()
                .build();
        HttpResponse<String> received = client.send(req, HttpResponse.BodyHandlers.ofString());
        log.received(received.statusCode());
        if (received.statusCode() != 429 && received.statusCode() < 500)
          return new Response(received.statusCode(), received.body());
        if (attempt >= policy.maxRetries())
          throw new TransportFailureException("Retry budget exhausted");
        backoff(attempt, received.headers().firstValue("Retry-After").orElse(""));
      } catch (IOException e) {
        if (attempt >= policy.maxRetries())
          throw new TransportFailureException("Transport failed", e);
        backoff(attempt, "");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TransportFailureException("Interrupted", e);
      }
    }
  }

  private void waitInterval() {
    lastRequest.ifPresent(
        at -> {
          Duration wait = Duration.between(clock.instant(), at.plus(policy.minInterval()));
          if (wait.isPositive()) sleeper.sleep(wait);
        });
  }

  private void backoff(int attempt, String retryAfter) {
    long seconds = 1L << attempt;
    try {
      seconds = Math.max(seconds, Long.parseLong(retryAfter));
    } catch (NumberFormatException ignored) {
      /* Only delta-seconds is supported. */
    }
    if (seconds > 60) throw new TransportFailureException("Retry-After exceeds one minute");
    sleeper.sleep(Duration.ofSeconds(seconds));
  }
}
