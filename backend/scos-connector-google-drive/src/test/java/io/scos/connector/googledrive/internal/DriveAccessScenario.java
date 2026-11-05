package io.scos.connector.googledrive.internal;

import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.FakeHttpServer;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/** 실제 어댑터와 실행기를 로컬 Google 권한 응답에 연결한다. */
final class DriveAccessScenario implements AutoCloseable {
  static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  private static final ObjectMapper JSON = new ObjectMapper();
  private final FakeHttpServer server;
  private final MutableClock clock = new MutableClock(NOW);
  private final CancellationToken cancel = new CancellationToken();
  private final ProviderCallExecutor calls =
      new ProviderCallExecutor(
          ProviderCallPolicy.defaults(() -> 0.0),
          clock,
          (delay, token) -> token.isCancelled(),
          TestMetrics.providerCalls());
  private String config =
      "{\"accountEmail\":\"reader@example.com\",\"permissionVisibility\":\"VERIFIED_FULL\"}";
  private DomainPolicy domains =
      new DomainPolicy(Set.of("example.com"), Map.of(SOURCE, "example.com"));
  private final String permission;
  private SourceInstanceId instance = SOURCE;
  private boolean cancelAfterPage;
  private boolean failSink;

  DriveAccessScenario() {
    try {
      server = new FakeHttpServer();
      try (var stream = DriveAccessScenario.class.getResourceAsStream("drive-permissions.json")) {
        if (stream == null) throw new IllegalStateException("given 준비 실패: 권한 fixture 없음");
        permission = JSON.readTree(stream).get("permissions").get(0).toString();
      }
      server.on(
          "POST",
          "/_oauth/token",
          request -> FakeHttpServer.ok("{\"access_token\":\"test-access\",\"expires_in\":3600}"));
      server.requireHeader("Authorization", "Bearer test-access", FakeHttpServer.status(401, "{}"));
      server.withoutHeaderCheck("POST", "/_oauth/token");
      server.limitRequests(16, FakeHttpServer.status(400, "{}"));
      permissions("F1", permission);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void nonCanonicalInstance() {
    instance = new SourceInstanceId("drive:a");
  }

  String user() {
    return permission;
  }

  void permissions(String id, String... values) {
    response(id, "{\"permissions\":[" + String.join(",", values) + "]}");
  }

  void response(String id, String body) {
    server.on("GET", path(id), request -> FakeHttpServer.ok(body));
  }

  void forbidden(String id, int status) {
    server.on("GET", path(id), request -> FakeHttpServer.status(status, "{}"));
  }

  void readerForbidden(boolean recorded) {
    if (!recorded) {
      forbiddenReason("F1", "insufficientFilePermissions");
      return;
    }
    try (var stream = getClass().getResourceAsStream("recorded/reader-forbidden.json")) {
      if (stream == null) throw new IllegalStateException("given 준비 실패: Drive 기록 표본 없음");
      forbiddenBody("F1", JSON.readTree(stream).toString());
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  void forbiddenReason(String id, String reason) {
    forbiddenBody(id, "{\"error\":{\"errors\":[{\"reason\":\"" + reason + "\"}]}}");
  }

  void forbiddenBody(String id, String body) {
    server.on("GET", path(id), request -> FakeHttpServer.status(403, body));
  }

  void pages(String id, String first, String last) {
    server.on(
        "GET",
        path(id),
        request -> FakeHttpServer.ok(request.params().containsKey("pageToken") ? last : first));
  }

  void retryTokenOnce() {
    AtomicInteger attempts = new AtomicInteger();
    server.on(
        "POST",
        "/_oauth/token",
        request ->
            attempts.incrementAndGet() == 1
                ? FakeHttpServer.status(503, "{}")
                : FakeHttpServer.ok("{\"access_token\":\"test-access\",\"expires_in\":3600}"));
  }

  void rejectToken() {
    server.on("POST", "/_oauth/token", request -> FakeHttpServer.status(400, "{}"));
  }

  void invalidTokenHeader() {
    server.on(
        "POST",
        "/_oauth/token",
        request -> FakeHttpServer.ok("{\"access_token\":\"invalid\\ntoken\",\"expires_in\":3600}"));
  }

  void retryThenPages() {
    AtomicInteger attempts = new AtomicInteger();
    server.on(
        "GET",
        path("F1"),
        request -> {
          clock.advance(Duration.ofMillis(10));
          if (attempts.incrementAndGet() == 1) return FakeHttpServer.status(503, "{}");
          return FakeHttpServer.ok(
              request.params().containsKey("pageToken")
                  ? "{\"permissions\":[]}"
                  : "{\"permissions\":[" + permission + "],\"nextPageToken\":\"page-2\"}");
        });
    for (String id : List.of("D1", "F2")) {
      server.on(
          "GET",
          path(id),
          request -> {
            clock.advance(Duration.ofMillis(10));
            return FakeHttpServer.ok("{\"permissions\":[]}");
          });
    }
  }

  void unverified() {
    config = "{\"accountEmail\":\"reader@example.com\"}";
  }

  void visibility(String value) {
    config = "{\"accountEmail\":\"reader@example.com\",\"permissionVisibility\":" + value + "}";
  }

  void organization(Set<String> allowed, String instanceDomain) {
    domains = new DomainPolicy(allowed, Map.of(SOURCE, instanceDomain));
  }

  void cancelBeforeCollect() {
    cancel.cancel();
  }

  void cancelAfterPage() {
    cancelAfterPage = true;
  }

  void failSink() {
    failSink = true;
  }

  Outcome collect(String... ids) {
    Outcome outcome = new Outcome();
    var adapter =
        GoogleDriveAssembly.adapter(server.baseUrl(), GoogleDriveAssembly.CREDENTIAL_JSON, clock);
    var source =
        new SourceDescriptor(
            instance,
            "google-drive",
            "Drive",
            new SourceConfig(1, config),
            GoogleDriveAssembly.CREDENTIAL_REF);
    try {
      adapter
          .accessPolicy()
          .collect(
              new AccessInput(source, List.of(ids), calls, cancel, domains),
              page -> {
                if (failSink) throw new IllegalStateException("injected access sink failure");
                outcome.pages.add(page);
                if (cancelAfterPage) cancel.cancel();
              });
    } catch (RuntimeException failure) {
      outcome.failure = Optional.of(failure);
    }
    return outcome;
  }

  List<FakeHttpServer.Recorded> requests() {
    return server.received().stream().filter(r -> r.method().equals("GET")).toList();
  }

  private static String path(String id) {
    return "/_drive/drive/v3/files/" + id + "/permissions";
  }

  @Override
  public void close() {
    server.close();
  }

  static final class Outcome {
    private final List<AclPage> pages = new ArrayList<>();
    private Optional<RuntimeException> failure = Optional.empty();

    Set<String> aclUnreadable() {
      return pages.stream()
          .flatMap(page -> page.aclUnreadable().stream())
          .collect(Collectors.toSet());
    }

    Optional<RuntimeException> failure() {
      return failure;
    }

    RawAcl.Observed acl(String id) {
      return (RawAcl.Observed) acls().get(id);
    }

    Map<String, RawAcl> acls() {
      Map<String, RawAcl> all = new LinkedHashMap<>();
      pages.forEach(page -> all.putAll(page.acls()));
      return all;
    }

    List<String> groups() {
      return pages.stream().flatMap(page -> page.groups().stream()).map(g -> g.groupId()).toList();
    }

    List<SourceIdentityRecord> identities() {
      return pages.stream().flatMap(page -> page.identities().stream()).toList();
    }
  }
}
