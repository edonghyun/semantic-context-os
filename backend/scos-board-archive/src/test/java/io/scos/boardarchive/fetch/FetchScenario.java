package io.scos.boardarchive.fetch;

import io.scos.boardarchive.driver.Response;
import io.scos.boardarchive.testkit.LocalSite;
import io.scos.boardarchive.testkit.MutableClock;
import io.scos.boardarchive.testsupport.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.Optional;

final class FetchScenario implements AutoCloseable {
  final LocalSite site;
  final MutableClock clock = new MutableClock();
  final FetchLog log = new FetchLog();
  private final HttpClient client = HttpClient.newHttpClient();
  private PoliteFetcher http;

  FetchScenario() throws Exception {
    site = new LocalSite();
    http = fetcher(Optional.empty());
  }

  private PoliteFetcher fetcher(Optional<Permission> permission) {
    return new PoliteFetcher(
        client,
        clock,
        clock::advance,
        new SitePolicy("archive-test", Duration.ofSeconds(3), 4, permission),
        log);
  }

  void givenRobots(String text) {
    site.respond("/robots.txt", 200, text);
  }

  void givenPermission() {
    http =
        fetcher(
            Optional.of(
                new Permission(
                    "maintainer",
                    LocalDate.of(2026, 11, 17),
                    "/private",
                    "local permission record")));
  }

  void givenResponses(int... statuses) {
    site.respond("/post", statuses[0], "body");
    for (int i = 1; i < statuses.length; i++) site.enqueue("/post", statuses[i], "body");
  }

  Response get(String path) {
    return http.get(site.uri(path));
  }

  @Override
  public void close() {
    client.close();
    site.close();
  }
}
