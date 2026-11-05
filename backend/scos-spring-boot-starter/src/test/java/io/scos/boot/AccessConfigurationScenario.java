package io.scos.boot;

import io.scos.collection.internal.AccessCollection;
import io.scos.collection.internal.BlobMaintenancePort;
import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.CollectionStores;
import io.scos.collection.internal.FixedSourceQueries;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunStore;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.collection.internal.memory.MemoryBodyCompletionStore;
import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceChangeLog;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.engine.internal.access.AccessResolver;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryDocumentOwnership;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemorySourceAccessDirectory;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

/** 실제 런타임 빈 조립에 계약 검증된 메모리 포트를 연결한다. */
final class AccessConfigurationScenario {
  private static final SourceInstanceId SOURCE = new SourceInstanceId("fixture-a");
  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  private final MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  private final MemoryRunStore runs = MemoryRunStore.create();
  private final MemoryRawItemStore raw = new MemoryRawItemStore();
  private final MemoryCheckpointPort checkpoints = new MemoryCheckpointPort();
  private final MemoryOutboxPort outbox = new MemoryOutboxPort();
  private final ManualTaskScheduler tokenScheduler = new ManualTaskScheduler();
  private final AccessAdapter adapter = new AccessAdapter();
  private final CollectionStores stores =
      new CollectionStores(
              TransactionOperations.withoutTransaction(),
              claims,
              runs,
              raw,
              checkpoints,
              outbox,
              new MemorySourceChangeLog(),
              raw.acls(),
              runs.accessObservations())
          .withBodies(new MemoryBodyCompletionStore())
          .withSources(sources);
  private AssertableApplicationContext context;
  private final AccessProperties.Mode mode;
  private String maxAge = "24h";
  private boolean collectAccess;
  private List<SourceInstance> domainSources = List.of();
  private List<String> orgDomains = List.of();
  private Map<String, String> instanceDomains = Map.of();

  private AccessConfigurationScenario(AccessProperties.Mode mode) {
    this.mode = mode;
  }

  void run(Consumer<AccessConfigurationScenario> test) {
    sources.save(SourceInstances.of(SOURCE, "fixture", "{}", "env:FAKE", NOW));
    new McpConfigurationScenario()
        .context(new MutableClock(NOW), new FixedSourceQueries(domainSources))
        .withPropertyValues("scos.access.max-age=" + maxAge)
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(CollectionConfiguration.class, Commits.class)
        .withPropertyValues(accessSettings())
        .withBean(CollectionStores.class, () -> stores)
        .withBean(SourceInstanceStore.class, () -> sources)
        .withBean(WorkClaimPort.class, () -> claims)
        .withBean(RunStore.class, () -> runs)
        .withBean(CheckpointPort.class, () -> checkpoints)
        .withBean(SnapshotGuardStore.class, MemorySnapshotGuardStore::new)
        .withBean(CollectionMetrics.class, TestMetrics::collection)
        .withBean(ProviderCallMetrics.class, TestMetrics::providerCalls)
        .withBean(SourceAdapter.class, () -> adapter)
        .withBean(
            ProjectionSpaces.class,
            () -> new ProjectionSpaces(Map.of(), SpaceId.LEGACY, new ObjectMapper()))
        .withBean("tokenScheduler", TaskScheduler.class, () -> tokenScheduler)
        .withBean(
            BlobMaintenancePort.class,
            () ->
                new PostgresCollectionConfiguration()
                    .blobMaintenancePort(JdbcClient.create(new DriverManagerDataSource())))
        .withBean(SourceChangeLog.class, stores::changes)
        .withBean(AclStore.class, MemoryAclStore::new)
        .withBean(DocumentOwnership.class, MemoryDocumentOwnership::new)
        .withBean(
            SourceAccessDirectory.class,
            () -> new MemorySourceAccessDirectory(new MemoryIdentityStore()))
        .run(
            ctx -> {
              context = ctx;
              test.accept(this);
            });
  }

  private String[] accessSettings() {
    var settings = new ArrayList<String>();
    settings.add("scos.access.mode=" + mode.name());
    if (collectAccess) settings.add("scos.access.collect=true");
    for (int i = 0; i < orgDomains.size(); i++)
      settings.add("scos.access.org-domains[" + i + "]=" + orgDomains.get(i));
    instanceDomains.forEach(
        (id, domain) -> settings.add("scos.access.instance-domains[" + id + "]=" + domain));
    return settings.toArray(String[]::new);
  }

  AccessConfigurationScenario driveAccount(String email) {
    return sourceConfiguration(
        "google-drive", "{\"accountEmail\":\"" + email + "\",\"downloadBinaries\":false}");
  }

  AccessConfigurationScenario withAccessCollection() {
    collectAccess = true;
    return this;
  }

  AccessConfigurationScenario sourceConfiguration(String kind, String config) {
    domainSources =
        List.of(SourceInstances.of(new SourceInstanceId("drive-a"), kind, config, "env:FAKE", NOW));
    return this;
  }

  AccessConfigurationScenario organization(String domain) {
    orgDomains = List.of(domain);
    return this;
  }

  AccessConfigurationScenario overlay(String domain) {
    instanceDomains = Map.of("drive-a", domain);
    return this;
  }

  DomainPolicy domains() {
    return ((AccessParts.Restricted) context.getBean(AccessParts.class)).domains();
  }

  DomainPolicy collectedDomains() {
    return adapter.domains.orElseThrow();
  }

  // 정책→Resolver 키 계약만 검증한다. 엔진의 정책 전달은 McpHttpTokenPoolsTest의 실제 PG 읽기가 검증한다.
  Set<AccessKey> resolveDomainKeys(String email) {
    var identities = new MemoryIdentityStore();
    identities.replace(
        new MemoryIdentityStore.Directory(
            List.of(new MemoryIdentityStore.User("member", email, true)),
            List.of(),
            List.of(),
            List.of(),
            Set.of()));
    var resolver = new AccessResolver(identities, domains());
    return ((AccessScope.Restricted) resolver.resolve(new Principal("member"))).keys();
  }

  AccessConfigurationScenario maxAge(String value) {
    maxAge = value;
    return this;
  }

  static AccessConfigurationScenario singleUser() {
    return new AccessConfigurationScenario(AccessProperties.Mode.SINGLE_USER);
  }

  static AccessConfigurationScenario multiUser() {
    return new AccessConfigurationScenario(AccessProperties.Mode.MULTI_USER);
  }

  void collect() {
    context
        .getBean(CollectionRuntime.class)
        .collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  int accessCalls() {
    return adapter.accessCalls;
  }

  long tokenSchedules() {
    return tokenScheduler.tasks().size();
  }

  CollectionRun lastRun() {
    return runs.latest(SOURCE).orElseThrow();
  }

  Map<String, RawAcl.Observed> rawAcls() {
    return raw.acls().get(SOURCE, List.of("item"));
  }

  AssertableApplicationContext context() {
    return context;
  }

  @Configuration(proxyBeanMethods = false)
  static class Commits {
    @Bean
    PageCommit pageCommit(
        CollectionStores stores,
        Clock clock,
        CollectionMetrics metrics,
        Progress progress,
        SourceAdapters adapters,
        AccessProperties access,
        AccessCollection.Options options) {
      return new PostgresCollectionConfiguration()
          .pageCommit(stores, clock, metrics, progress, adapters, access, options);
    }

    @Bean
    RunCompletion runCompletion(CollectionStores stores, SnapshotShrinkGuard guard) {
      return new PostgresCollectionConfiguration().runCompletion(stores, guard);
    }
  }

  private static final class AccessAdapter implements SourceAdapter {
    private static final RawAcl.Observed ACL =
        RawAcl.observed(
            Status.OK, Set.of(AccessKey.parse("user:fixture-a:member@example.com")), NOW);
    private int accessCalls;
    private Optional<DomainPolicy> domains = Optional.empty();

    @Override
    public String kind() {
      return "fixture";
    }

    @Override
    public AccessPolicy accessPolicy() {
      return AccessPolicy.supported(Set.of("fixture.item"), Set.of())
          .collecting(
              (input, sink) -> {
                accessCalls++;
                domains = Optional.of(input.domains());
                sink.accept(new AclPage(Map.of("item", ACL), List.of(), List.of()));
              });
    }

    @Override
    public void validateConfig(SourceConfig config) {}

    @Override
    public CollectionCoverage coverage(
        SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
      return CollectionCoverage.INCREMENTAL;
    }

    @Override
    public void collect(CollectionInput input, PageSink sink) {
      sink.accept(
          CollectionPage.ofItems(
              List.of(
                  RawItems.item("item")
                      .resourceType("fixture.item")
                      .metadata("{\"id\":\"item\",\"title\":\"Release checklist\"}")
                      .acl(ACL)
                      .build()),
              new Checkpoint(1, "{}")));
    }
  }
}
