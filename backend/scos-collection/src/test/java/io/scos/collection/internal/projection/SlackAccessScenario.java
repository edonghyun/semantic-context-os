package io.scos.collection.internal.projection;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.connector.slack.internal.FakeSlackServer.Fault;
import io.scos.engine.ContextEngine;
import io.scos.engine.DocumentPage;
import io.scos.engine.ReadRequest;
import io.scos.engine.SearchQuery;
import io.scos.engine.SearchResult;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.AclSink;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/** 실제 Slack HTTP·수집·병합·투영·요청별 키 조회를 연결한다. 사용자 연결만 테스트 시드다. */
@Tag("db")
final class SlackAccessScenario implements AutoCloseable {
  private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
  private static final SpaceId SPACE = new SpaceId("legacy");
  private static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  private static final DomainPolicy DOMAINS = new DomainPolicy(Set.of(), Map.of());
  private static final String DOC = "C1/day-2025-10-14";
  private final MutableClock clock = new MutableClock(T0);
  private final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());
  private final PostgresCollectionConfiguration.Adapters stores =
      PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
  private final FakeSlackServer server;
  private final ObservedSlack adapter;
  private final PostgresCollectionRuntime collection;
  private final PostgresProjection projection;
  private final ContextEngine engine;

  SlackAccessScenario() throws IOException {
    PostgresSupport.truncateAll();
    server =
        new FakeSlackServer(
            Path.of(
                "scos-collection/src/test/resources/io/scos/collection/internal/slack-access-dataset.json"));
    adapter = new ObservedSlack(SlackAssembly.adapter(server.baseUrl(), server.token(), clock));
    collection =
        PostgresCollectionRuntime.over(PostgresSupport.dataSource(), clock)
            .adapter(adapter)
            .collectAccess(true);
    projection =
        PostgresProjection.collectingAccess(
            PostgresSupport.dataSource(), clock, new ScriptedEmbedder(), adapter);
    stores
        .sources()
        .save(
            SourceInstances.of(
                SOURCE,
                "slack",
                "{\"channelIds\":[\"C1\"],\"conversationTypes\":[\"public_channel\",\"private_channel\"],\"historyOverlapSeconds\":0}",
                "env:SLACK_LOCAL_FAKE",
                T0));
    seedUsers();
    var index = new PostgresIndexConfiguration();
    engine =
        ContextEngine.builder()
            .units(PostgresTestAdapters.unitStore(PostgresSupport.dataSource()))
            .keywords(PostgresTestAdapters.keywordIndex(PostgresSupport.dataSource()))
            .keywordOnly()
            .access(
                index.newIdentityStore(PostgresSupport.dataSource()),
                PostgresCollectionRuntime.sourceFreshness(PostgresSupport.dataSource()),
                DOMAINS)
            .clock(clock)
            .lexicalExecutor(TestExecutors.CALLER_THREAD)
            .build();
  }

  private void seedUsers() {
    for (String id : List.of("U1", "U2", "U3")) {
      jdbc.sql("INSERT INTO users VALUES (:id,:email,true,'member',:at)")
          .param("id", id)
          .param("email", id.toLowerCase(Locale.ROOT) + "@example.com")
          .param("at", Timestamp.from(T0))
          .update();
      jdbc.sql("INSERT INTO user_source_identities VALUES (:source,:id,:id,'admin',:at,:id)")
          .param("source", SOURCE.value())
          .param("id", id)
          .param("at", Timestamp.from(T0))
          .update();
    }
  }

  void givenPublicDocument() {
    collectAndProject();
    requireVisible("U1");
    requireVisible("U2");
  }

  void givenPrivateDocument() {
    makePrivate();
    collectAndProject();
    requireVisible("U2");
    requireHidden("U3");
  }

  void givenPrivateDocumentWithU1() {
    makePrivate();
    replaceParticipants("U1", "U2");
    collectAndProject();
    requireVisible("U1");
    requireVisible("U2");
    requireHidden("U3");
  }

  private void requireVisible(String user) {
    if (search(user).hits().size() != 1 || read(user).units().isEmpty())
      throw new IllegalStateException("given 준비 실패: 문서가 보여야 한다 — " + user);
  }

  private void requireHidden(String user) {
    if (!search(user).hits().isEmpty())
      throw new IllegalStateException("given 준비 실패: 비참여자에게 문서가 보인다 — " + user);
  }

  void makePrivate() {
    server.changeAccess(
        access -> ((ObjectNode) access.path("conversations").path("C1")).put("is_private", true));
  }

  void replaceParticipants(String... ids) {
    server.changeAccess(
        access -> {
          var members = ((ObjectNode) access.path("memberPages")).putArray("C1").addArray();
          for (String id : ids) members.add(id);
        });
  }

  void removeParticipant() {
    replaceParticipants();
  }

  void missingIdentityInOtherChannel() {
    server.addAccessChannel("C2", Set.of("U9"));
    adapter.omitIdentity = "U9";
  }

  void unlistAllChannels() {
    server.clearChannels();
  }

  void failBody() {
    server.failNext("/api/conversations.history", 1, Fault.apiError("missing_scope"));
  }

  CollectionRun collectAndProject() {
    var run = collect();
    project();
    return run;
  }

  private CollectionRun collect() {
    clock.advance(Duration.ofSeconds(1));
    collection.build().collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    return stores.runs().latest(SOURCE).orElseThrow();
  }

  private void project() {
    var report =
        projection.run("slack-acl", new ManualTaskScheduler()).runDue(CancellationToken.none());
    if (report.stream().anyMatch(r -> r.outcome() != ProjectionRun.Outcome.SUCCEEDED))
      throw new IllegalStateException("투영 실패: " + report);
  }

  SearchResult search(String user) {
    return engine.search(new Principal(user), SearchQuery.of("deployment"));
  }

  DocumentPage read(String user) {
    return engine.read(new Principal(user), ReadRequest.of(DOC));
  }

  List<String> channelKeys(String user) {
    return new PostgresIndexConfiguration()
            .newIdentityStore(PostgresSupport.dataSource())
            .accessKeysOf(user, DOMAINS)
            .orElseThrow()
            .stream()
            .map(Object::toString)
            .filter(key -> key.startsWith("group:slack-a:") && !key.startsWith("group:slack-a:~"))
            .sorted()
            .toList();
  }

  boolean collectorReturnedNormally() {
    return adapter.returnedNormally;
  }

  boolean hasIdentity(String id) {
    return jdbc.sql("SELECT EXISTS(SELECT 1 FROM source_identities WHERE source_user_id=:id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  void givenHistoricalDocument() {
    stores
        .rawItems()
        .apply(
            SOURCE,
            RawItems.item("C1:1760400000.000100")
                .version("1760400000.000100")
                .metadata("{\"channelId\":\"C1\",\"messageTs\":\"1760400000.000100\"}")
                .inlineJson(
                    "{\"text\":\"Release checklist documents deployment procedures for workspace maintainers and all release participants.\"}")
                .upsert());
    stores.outbox().requestProjection(SOURCE, clock.instant());
    PostgresProjection.withoutAccessCollection(
            PostgresSupport.dataSource(), clock, new ScriptedEmbedder())
        .run("legacy-slack", new ManualTaskScheduler())
        .runDue(CancellationToken.none());
    if (stores.rawAcls().documentsMissingAcl(SPACE, SOURCE) != 1)
      throw new IllegalStateException("given 준비 실패: 기존 문서의 ACL 하나가 누락돼야 한다");
    server.clearChannels();
  }

  record Backfill(int missing, long additionalRequests) {}

  Backfill backfillThenRepeat() {
    collectAndProject();
    int missing = stores.rawAcls().documentsMissingAcl(SPACE, SOURCE);
    if (jdbc.sql("SELECT count(*)::int FROM raw_acl").query(Integer.class).single() != 1)
      throw new IllegalStateException("보충된 원본 ACL이 없다");
    collect();
    return new Backfill(
        missing, stores.outbox().request(SOURCE).map(OutboxPort.Request::version).orElse(0L));
  }

  @Override
  public void close() {
    server.close();
  }

  /** 관측 신원 하나만 누락시켜 저장소의 COMPLETE 전제 검사를 통과하게 하지 않는다. */
  private static final class ObservedSlack implements SourceAdapter {
    private final SourceAdapter delegate;
    private String omitIdentity = "";
    private boolean returnedNormally;

    ObservedSlack(SourceAdapter delegate) {
      this.delegate = delegate;
    }

    @Override
    public String kind() {
      return delegate.kind();
    }

    @Override
    public void validateConfig(SourceConfig config) {
      delegate.validateConfig(config);
    }

    @Override
    public CollectionCoverage coverage(
        SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
      return delegate.coverage(config, mode, checkpoint);
    }

    @Override
    public void collect(CollectionInput input, PageSink sink) {
      delegate.collect(input, sink);
    }

    @Override
    public AccessPolicy accessPolicy() {
      return delegate.accessPolicy().collecting(this::collectAccess);
    }

    private void collectAccess(AccessInput input, AclSink sink) {
      returnedNormally = false;
      delegate
          .accessPolicy()
          .collect(
              input,
              page ->
                  sink.accept(
                      new AclPage(
                          page.acls(),
                          page.groups(),
                          page.identities().stream()
                              .filter(identity -> !identity.sourceUserId().equals(omitIdentity))
                              .toList())));
      returnedNormally = true;
    }
  }
}
