package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.notion.NotionAssembly;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessPolicy;
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
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore.Range;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ManualTaskScheduler;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("Notion의 기존 문서는 본문 재수집 없이 ACL을 보충한다")
class NotionAccessBackfillPostgresTest {
  BackfillScenario scenario;

  @BeforeEach
  void setUp() {
    PostgresSupport.truncateAll();
    scenario = new BackfillScenario();
  }

  @Test
  @DisplayName("첫 enabled run은 원본 ACL 없이 기존 페이지 ACL을 보충한다")
  void existingPageGetsAclWithoutRawAcl() {
    scenario.collectThenProject();
    // then
    assertThat(
            List.of(
                scenario.rawAclCount(),
                scenario.missing(),
                scenario.adapters.runs().latest(BackfillScenario.SOURCE).orElseThrow().upserted()))
        .containsExactly(0, 0, 0);
    assertThat(scenario.acl())
        .extracting(DocumentAcl::status)
        .isEqualTo(DocumentAcl.Status.UNSUPPORTED);
  }

  @Test
  @DisplayName("소유 행이 없어도 실제 문서의 누락은 보충 요청을 만든다")
  void legacyUnitWithoutOwnershipRequestsProjection() {
    scenario.removeOwnership();
    scenario.collect();
    // then
    assertThat(scenario.adapters.outbox().request(BackfillScenario.SOURCE)).isPresent();
  }

  @Test
  @DisplayName("보충이 끝난 다음 run은 같은 문서를 다시 요청하지 않는다")
  void completedBackfillDoesNotReprojectEveryRun() {
    scenario.collectThenProject();
    scenario.collect();
    // then
    assertThat(scenario.adapters.outbox().request(BackfillScenario.SOURCE)).isEmpty();
  }

  @Test
  @DisplayName("prune 뒤 소유 행만 남은 문서는 누락으로 세지 않는다")
  void deletedOwnershipDoesNotCountAsMissingAcl() {
    scenario.deletePage();
    // then
    assertThat(scenario.missing()).isZero();
  }

  @Test
  @DisplayName("소스 지정 뒤 같은 요청자가 보충된 페이지를 읽을 수 있다")
  void designationRevealsPreviouslyProjectedPage() {
    scenario.collectThenProject();
    assertThat(scenario.readAsMember()).isEmpty();
    scenario.designateSource();
    // then
    assertThat(scenario.readAsMember()).isNotEmpty();
  }

  static final class BackfillScenario {
    static final SourceInstanceId SOURCE = new SourceInstanceId("notion-a");
    static final SpaceId SPACE = new SpaceId("legacy");
    static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
    final MutableClock clock = new MutableClock(T0);
    final PostgresCollectionConfiguration.Adapters adapters =
        PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
    final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());
    final SourceAdapter adapter = new UnchangedNotion();
    final PostgresCollectionRuntime runtime =
        PostgresCollectionRuntime.over(PostgresSupport.dataSource(), clock)
            .adapter(adapter)
            .collectAccess(true);

    BackfillScenario() {
      adapters.sources().save(SourceInstances.of(SOURCE, "notion", "{}", "env:FAKE", T0));
      adapters
          .rawItems()
          .apply(
              SOURCE,
              RawItems.item("P1")
                  .resourceType("notion.page")
                  .metadata("{\"url\":\"notion://P1\"}")
                  .inlineJson(
                      "{\"properties\":{\"Name\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"Page\"}]}}}")
                  .upsert());
      adapters
          .rawItems()
          .apply(
              SOURCE,
              RawItems.item("block:B1")
                  .resourceType("notion.block")
                  .metadata("{\"type\":\"paragraph\",\"parentBlockId\":\"P1\"}")
                  .inlineJson(
                      "{\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":[{\"plain_text\":\"Existing project documentation describes the release process and the access review checklist for everyone maintaining this workspace.\"}]}}")
                  .upsert());
      adapters.outbox().requestProjection(SOURCE, T0);
      PostgresProjection.withoutAccessCollection(
              PostgresSupport.dataSource(), clock, new ScriptedEmbedder())
          .run("legacy", new ManualTaskScheduler())
          .runDue(CancellationToken.none());
      if (PostgresTestAdapters.unitMaintenance(PostgresSupport.dataSource())
          .listAll(SPACE)
          .isEmpty()) throw new IllegalStateException("given 준비 실패: 기존 페이지 유닛이 없다");
      jdbc.sql(
              "INSERT INTO users (id,email,active,role,created_at) VALUES ('member','m@example.com',true,'member',:at)")
          .param("at", Timestamp.from(T0))
          .update();
    }

    void collect() {
      clock.advance(Duration.ofSeconds(1));
      runtime.build().collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    }

    void collectThenProject() {
      collect();
      PostgresProjection.collectingAccess(
              PostgresSupport.dataSource(), clock, new ScriptedEmbedder(), adapter)
          .run("backfill", new ManualTaskScheduler())
          .runDue(CancellationToken.none());
    }

    int missing() {
      return adapters.rawAcls().documentsMissingAcl(SPACE, SOURCE);
    }

    int rawAclCount() {
      return jdbc.sql("SELECT count(*)::int FROM raw_acl").query(Integer.class).single();
    }

    DocumentAcl acl() {
      return new PostgresIndexConfiguration()
          .newAclStore(PostgresSupport.dataSource())
          .get(SPACE, List.of("P1"))
          .get("P1");
    }

    void removeOwnership() {
      jdbc.sql("DELETE FROM document_spaces").update();
    }

    void deletePage() {
      PostgresTestAdapters.unitMaintenance(PostgresSupport.dataSource())
          .deleteByDocument(SPACE, SOURCE.value(), "P1", List.of());
    }

    void designateSource() {
      jdbc.sql("INSERT INTO org_wide_scopes VALUES ('notion-a','admin',:at)")
          .param("at", Timestamp.from(clock.instant()))
          .update();
    }

    List<ContextUnit> readAsMember() {
      Set<AccessKey> keys =
          new PostgresIndexConfiguration()
              .newIdentityStore(PostgresSupport.dataSource())
              .accessKeysOf("member", new DomainPolicy(Set.of(), Map.of()))
              .orElseThrow();
      return PostgresTestAdapters.unitStore(PostgresSupport.dataSource())
          .listByDocument(
              SPACE,
              new AccessScope.Restricted(new Principal("member"), keys),
              "P1",
              new Range(0, 100));
    }
  }

  static final class UnchangedNotion implements SourceAdapter {
    private final SourceAdapter declaration =
        NotionAssembly.adapter("http://127.0.0.1:1", "synthetic", Clock.systemUTC());

    @Override
    public String kind() {
      return "notion";
    }

    @Override
    public AccessPolicy accessPolicy() {
      return declaration.accessPolicy();
    }

    @Override
    public void validateConfig(SourceConfig config) {}

    @Override
    public CollectionCoverage coverage(
        SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
      return CollectionCoverage.INCREMENTAL;
    }

    @Override
    public void collect(CollectionInput input, PageSink sink) {}
  }
}
