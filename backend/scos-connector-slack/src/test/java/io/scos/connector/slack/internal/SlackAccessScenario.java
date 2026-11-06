package io.scos.connector.slack.internal;

import io.scos.collection.internal.AccessMerge;
import io.scos.collection.internal.AccessMergePolicy;
import io.scos.collection.internal.RawContentCapture;
import io.scos.connector.slack.SlackAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 실제 어댑터·호출 실행기와 로컬 Slack 서버의 접근 관측을 연결한다. */
final class SlackAccessScenario implements AutoCloseable {
  static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  static final String MESSAGE = "C1:1700000000.000001";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ObjectNode dataset;
  private final FakeSlackServer server;
  private final List<Duration> waits = new ArrayList<>();
  private final MutableClock clock = new MutableClock(NOW);
  private final CancellationToken cancel = new CancellationToken();
  private String failSinkGroup = "";
  private boolean trailingSlash;
  private boolean cancelOnRetry;
  private Duration pageDuration = Duration.ZERO;
  private final ProviderCallExecutor calls =
      new ProviderCallExecutor(
          ProviderCallPolicy.defaults(() -> 0.0),
          clock,
          (delay, cancel) -> {
            waits.add(delay);
            if (cancelOnRetry) cancel.cancel();
            return cancel.isCancelled();
          },
          TestMetrics.providerCalls());

  SlackAccessScenario() {
    try {
      dataset =
          (ObjectNode)
              JSON.readTree(
                  Path.of(
                          "scos-connector-slack/src/test/resources/io/scos/connector/slack/internal/slack-access-dataset.json")
                      .toFile());
      server = new FakeSlackServer(dataset);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void channelFlag(String name, boolean value) {
    changeAccess(access -> ((ObjectNode) access.path("conversations").path("C1")).put(name, value));
  }

  void directMessageWithoutPrivateFlag(String flag) {
    channelFlag(flag, true);
    changeAccess(
        access -> {
          var channel = (ObjectNode) access.path("conversations").path("C1");
          channel.remove("is_private");
          channel.remove(flag.equals("is_im") ? "is_mpim" : "is_im");
        });
  }

  void givenResponseShape(boolean recorded) {
    if (recorded) recordedResponses();
    else {
      channelFlag("is_im", true);
      changeAccess(
          access -> {
            var users = (ArrayNode) access.path("userPages").get(0);
            users
                .addObject()
                .put("id", "U3")
                .put("team_id", "T1")
                .put("deleted", true)
                .putObject("profile");
            users
                .addObject()
                .put("id", "U4")
                .put("team_id", "T1")
                .put("deleted", false)
                .put("is_bot", true)
                .put("is_restricted", false)
                .put("is_ultra_restricted", false)
                .putObject("profile");
          });
    }
  }

  private void recordedResponses() {
    try (var stream = getClass().getResourceAsStream("recorded/access-responses.json")) {
      if (stream == null) throw new IllegalStateException("given 준비 실패: Slack 기록 표본 없음");
      var responses = JSON.readTree(stream);
      changeAccess(
          access -> {
            ((ObjectNode) access.path("conversations"))
                .set("C1", responses.path("dm").path("channel"));
            ((ArrayNode) access.path("userPages")).set(0, responses.path("users").path("members"));
          });
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  void unreadableChannel(String method, boolean recorded) {
    String body = "{\"ok\":false,\"error\":\"channel_not_found\"}";
    if (recorded) {
      try (var stream = getClass().getResourceAsStream("recorded/access-responses.json")) {
        if (stream == null) throw new IllegalStateException("given 준비 실패: Slack 기록 표본 없음");
        body = JSON.readTree(stream).path("channelNotFound").toString();
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    }
    failNext("/api/conversations." + method, FakeSlackServer.Fault.http(200, body));
  }

  void baseUrlHasTrailingSlash() {
    trailingSlash = true;
  }

  private String baseUrl() {
    return server.baseUrl() + (trailingSlash ? "/" : "");
  }

  void user(String id, Consumer<ObjectNode> change) {
    changeAccess(
        access -> {
          for (JsonNode page : access.path("userPages"))
            for (JsonNode user : page)
              if (user.path("id").asString().equals(id)) change.accept((ObjectNode) user);
        });
  }

  void deletedUserWithoutEligibilityFlags() {
    user(
        "U1",
        user -> {
          user.put("deleted", true);
          user.remove("is_restricted");
          user.remove("is_ultra_restricted");
          user.remove("is_bot");
          user.remove("is_email_confirmed");
        });
  }

  void memberPages(String pagesJson) {
    changeAccess(
        access -> ((ObjectNode) access.path("memberPages")).set("C1", JSON.readTree(pagesJson)));
  }

  void changeAccess(Consumer<ObjectNode> change) {
    server.changeAccess(change);
  }

  void unlistChannel() {
    server.clearChannels();
  }

  void secondChannel() {
    server.addAccessChannel("C2", Set.of("U1"));
  }

  void splitUsersAcrossPages() {
    changeAccess(
        access -> {
          ArrayNode pages = (ArrayNode) access.path("userPages");
          JsonNode second = ((ArrayNode) pages.get(0)).remove(1);
          pages.addArray().add(second);
        });
  }

  void cancelOnRetry() {
    cancelOnRetry = true;
  }

  void cancel() {
    cancel.cancel();
  }

  void failSinkOnGroup(String id) {
    failSinkGroup = id;
  }

  void repeatCursor(String path) {
    server.repeatCursor(path);
  }

  void failAttempts(String path, FakeSlackServer.Fault fault) {
    server.failNext(path, 5, fault);
  }

  void failNext(String path, FakeSlackServer.Fault fault) {
    server.failNext(path, 1, fault);
  }

  Outcome collectMessages(int count) {
    String[] ids =
        IntStream.range(0, count)
            .mapToObj(index -> "C1:1700000000." + String.format(Locale.ROOT, "%06d", index))
            .toArray(String[]::new);
    return collect(ids);
  }

  void advanceClockBetweenPages() {
    pageDuration = Duration.ofSeconds(1);
  }

  Outcome collect(String... externalIds) {
    Outcome out = new Outcome();
    try {
      SlackAssembly.adapter(baseUrl(), server.token(), clock)
          .accessPolicy()
          .collect(
              new AccessInput(
                  SlackAssembly.instance("{}", NOW).descriptor(),
                  List.of(externalIds),
                  calls,
                  cancel,
                  new DomainPolicy(Set.of(), Map.of())),
              page -> {
                if (page.groups().stream().anyMatch(group -> group.groupId().equals(failSinkGroup)))
                  throw new IllegalStateException("injected ACL sink failure");
                out.pages.add(page);
                clock.advance(pageDuration);
              });
    } catch (RuntimeException e) {
      out.failure = Optional.of(e);
    }
    return out;
  }

  Optional<RawAcl> localAcl(String externalId) {
    return SlackAssembly.adapter(baseUrl(), server.token(), clock)
        .accessPolicy()
        .localAcl(SlackAssembly.instance("{}", NOW).descriptor(), externalId, NOW);
  }

  RawAcl bodyAcl() {
    List<CollectionPage> pages = new ArrayList<>();
    SlackAssembly.adapter(baseUrl(), server.token(), clock)
        .collect(
            new CollectionInput(
                SlackAssembly.instance("{}", NOW).descriptor(),
                Optional.empty(),
                CollectionMode.INCREMENTAL,
                calls,
                RawContentCapture.defaults(),
                cancel),
            pages::add);
    return ((RawMutation.Upsert) pages.getFirst().mutations().getFirst()).item().acl();
  }

  List<String> requests() {
    return server.requests();
  }

  JsonNode response(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .header("Authorization", "Bearer " + server.token())
            .build();
    return JSON.readTree(
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
  }

  List<Duration> waits() {
    return List.copyOf(waits);
  }

  @Override
  public void close() {
    server.close();
  }

  static final class Outcome {
    private final List<AclPage> pages = new ArrayList<>();
    private Optional<RuntimeException> failure = Optional.empty();

    Optional<RuntimeException> failure() {
      return failure;
    }

    List<GroupSnapshot> groups() {
      return pages.stream().flatMap(page -> page.groups().stream()).toList();
    }

    GroupSnapshot group(String id) {
      return groups().stream()
          .filter(group -> group.groupId().equals(id))
          .findFirst()
          .orElseThrow();
    }

    SourceIdentityRecord identity(String id) {
      return identities().stream()
          .filter(identity -> identity.sourceUserId().equals(id))
          .findFirst()
          .orElseThrow();
    }

    List<SourceIdentityRecord> identities() {
      return pages.stream().flatMap(page -> page.identities().stream()).toList();
    }

    Map<String, RawAcl> acls() {
      Map<String, RawAcl> acls = new LinkedHashMap<>();
      pages.forEach(page -> acls.putAll(page.acls()));
      return acls;
    }

    List<RawAcl> observationsOf(String id) {
      return pages.stream()
          .filter(page -> page.acls().containsKey(id))
          .map(page -> page.acls().get(id))
          .toList();
    }

    int largestAclPage() {
      return pages.stream().mapToInt(page -> page.acls().size()).max().orElse(0);
    }

    Set<String> unreadableChannels() {
      return pages.stream()
          .flatMap(page -> page.aclUnreadable().stream())
          .collect(Collectors.toSet());
    }

    Set<String> mergedOldChannelMembers() {
      var observations = AccessMergePolicy.Observations.empty();
      for (AclPage page : pages) observations = observations.observe(page);
      var current =
          new Snapshot(
              Map.of(),
              Map.of("COLD", new GroupSnapshot("COLD", Set.of("U1"), NOW.minusSeconds(1))));
      return AccessMergePolicy.merge(
              failure.isEmpty() ? AccessMerge.COMPLETE : AccessMerge.INCOMPLETE,
              current,
              observations.snapshot(),
              NOW)
          .snapshot()
          .groups()
          .get("COLD")
          .members();
    }
  }
}
