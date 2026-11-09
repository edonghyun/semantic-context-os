package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.AccessCollection;
import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunOutcome;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceQueries.AccessStatus;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.slack.SlackAssembly;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@Tag("db")
@DisplayName("접근 상태는 본문 성공과 별도로 실제 저장 상태를 조회한다")
class AccessStatusPostgresTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final Instant T1 = T0.plusSeconds(1);
  StatusScenario scenario;

  @BeforeEach
  void setUp() {
    PostgresSupport.truncateAll();
    scenario = new StatusScenario();
  }

  @Test
  @DisplayName("재관측 대상 Slack carrier가 관측 상한에 닿으면 stale로 집계한다")
  void slackCarriersBecomeStale() {
    scenario.givenSlackCarrier();
    var status =
        scenario
            .queriesAt(T0.plusSeconds(86400))
            .accessStatus(new SourceInstanceId("slack-a"))
            .orElseThrow();
    // then
    assertThat(status.rawStale()).isEqualTo(1);
  }

  @Test
  @DisplayName("실행 중 run은 연속 미완료 수를 늘리지 않는다")
  void incompleteTrendIsVisibleWhileAnotherRunIsRunning() {
    scenario.givenAccessHistory();
    var status = scenario.adapters.queries().accessStatus(SOURCE).orElseThrow();
    // then
    assertThat(status)
        .extracting("consecutiveIncomplete", "lastCompleteAt", "running")
        .containsExactly(2, Optional.of(T1), true);
  }

  @Test
  @DisplayName("누락·FAILED·UNKNOWN ACL 문서는 숨김 수에 포함한다")
  void hiddenDocumentsIncludeMissingAndClosedAcls() {
    scenario.givenHiddenDocuments();
    var status = scenario.adapters.queries().accessStatus(SOURCE).orElseThrow();
    // then
    assertThat(status).extracting("documentsHidden").isEqualTo(3);
  }

  @Test
  @DisplayName("접근 수집 설정과 무관하게 소유 충돌 수를 소스 상태로 조회한다")
  void sourceStatusIncludesOwnershipSkipped() {
    scenario.adapters.sources().recordOwnershipSkipped(SOURCE, 2);
    var status = scenario.adapters.queries().status(SOURCE, T1).orElseThrow();
    // then
    assertThat(status.ownershipSkipped()).isEqualTo(2);
  }

  @Test
  @DisplayName("본문 성공과 접근 미완료·종료 시각·원본 상태·문서 누락을 분리한다")
  void reportsAccessSeparatelyFromBody() {
    scenario.givenIncompleteAccess();
    var status = scenario.adapters.queries().accessStatus(SOURCE).orElseThrow();
    // then
    assertThat(scenario.adapters.runs().latest(SOURCE).orElseThrow().status())
        .isEqualTo(RunStatus.SUCCEEDED);
    assertThat(status)
        .extracting(AccessStatus::result, AccessStatus::finishedAt)
        .containsExactly(Optional.of(AccessResult.INCOMPLETE), Optional.of(T1));
    assertThat(status)
        .extracting(
            AccessStatus::rawUnknown, AccessStatus::rawFailed, AccessStatus::documentsMissingAcl)
        .containsExactly(2, 1, 1);
  }

  @Test
  @DisplayName("등록됐으나 실행 전인 소스는 접근 결과와 종료 시각이 없다")
  void neverRunHasNoResult() {
    var status = scenario.adapters.queries().accessStatus(SOURCE).orElseThrow();
    // then
    assertThat(status.result()).isEmpty();
    assertThat(status.finishedAt()).isEmpty();
  }

  @Test
  @DisplayName("없는 소스의 접근 상태는 없다")
  void absentSourceHasNoStatus() {
    // then
    assertThat(scenario.adapters.queries().accessStatus(new SourceInstanceId("absent"))).isEmpty();
  }

  @Test
  @DisplayName("원본 ACL 행 없는 활성 대상은 미관측으로 드러난다")
  void exposesUnobservedTargets() {
    scenario
        .adapters
        .rawItems()
        .apply(SOURCE, RawItems.item("file:new").resourceType("gdrive.file").upsert());
    var status = scenario.adapters.queries().accessStatus(SOURCE).orElseThrow();
    // then
    assertThat(status.rawUnobserved()).isEqualTo(1);
  }

  @Test
  @DisplayName("상한에 도달한 원본 수와 가장 오래된 실제 관측 시각을 노출한다")
  void exposesLongUnobservedTargets() {
    scenario.givenIncompleteAccess();
    var status = scenario.queriesAt(T0.plusSeconds(86400)).accessStatus(SOURCE).orElseThrow();
    // then
    assertThat(status)
        .extracting(AccessStatus::rawStale, AccessStatus::oldestObservedAt)
        .containsExactly(3, Optional.of(T0));
  }

  @Test
  @DisplayName("상한 직전 원본은 장기 미관측에 포함하지 않는다")
  void doesNotReportFreshTargetsAsStale() {
    scenario.givenIncompleteAccess();
    var status = scenario.queriesAt(T0.plusSeconds(86399)).accessStatus(SOURCE).orElseThrow();
    // then
    assertThat(status.rawStale()).isZero();
  }

  @Test
  @DisplayName("활성 원본 없는 고아 ACL은 보충 상태에서 제외한다")
  void orphanAclDoesNotCount() {
    scenario
        .adapters
        .rawAcls()
        .observe(SOURCE, "orphan", RawAcl.observed(Status.FAILED, Set.of(), T0), T0);
    var status = scenario.adapters.queries().accessStatus(SOURCE).orElseThrow();
    // then
    assertThat(status)
        .extracting(AccessStatus::rawFailed, AccessStatus::rawStale, AccessStatus::oldestObservedAt)
        .containsExactly(0, 0, Optional.empty());
  }

  @Test
  @DisplayName("정리된 소유 행은 실제 문서의 숨김과 누락 집계에서 빠진다")
  void hiddenCountsExcludePrunedOwnerRows() {
    // given
    scenario.givenHiddenDocumentsWithPrunedOwner();
    // when
    var status = scenario.adapters.queries().accessStatus(SOURCE).orElseThrow();
    // then
    assertThat(status)
        .extracting(AccessStatus::documentsHidden, AccessStatus::documentsMissingAcl)
        .containsExactly(2, 0);
  }

  static final class StatusScenario {
    final PostgresCollectionConfiguration.Adapters adapters =
        PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());

    StatusScenario() {
      adapters.sources().save(SourceInstances.of(SOURCE, "google-drive", "{}", "env:FAKE", T0));
    }

    SourceQueries queriesAt(Instant at) {
      return new PostgresSourceQueries(
          JdbcClient.create(PostgresSupport.dataSource()),
          adapters.guards(),
          adapters.rawAcls(),
          new ProjectionSpaces(Map.of(), SpaceId.LEGACY, new ObjectMapper()),
          Clock.fixed(at, ZoneOffset.UTC),
          AccessCollection.Options.DEFAULTS,
          SourceAdapters.of(
              List.of(
                  GoogleDriveAssembly.adapter(
                      "http://127.0.0.1:1", "synthetic", Clock.fixed(at, ZoneOffset.UTC)),
                  SlackAssembly.adapter(
                      "http://127.0.0.1:1", "synthetic", Clock.fixed(at, ZoneOffset.UTC)))),
          new PostgresIndexConfiguration().newAclStore(PostgresSupport.dataSource()));
    }

    void givenSlackCarrier() {
      var slack = new SourceInstanceId("slack-a");
      adapters.sources().save(SourceInstances.of(slack, "slack", "{}", "env:FAKE", T0));
      adapters.rawItems().apply(slack, RawItems.item("C1:1700000000.000001").upsert());
      adapters
          .rawAcls()
          .observe(slack, "C1:1700000000.000001", RawAcl.observed(Status.OK, Set.of(), T0), T0);
    }

    void givenAccessHistory() {
      finishedAccess("complete", AccessResult.COMPLETE, T0, T1);
      finishedAccess("incomplete-1", AccessResult.INCOMPLETE, T1, T1.plusSeconds(1));
      finishedAccess("incomplete-2", AccessResult.CLAIM_LOST, T1.plusSeconds(1), T1.plusSeconds(2));
      adapters.runs().start(new RunId("running"), SOURCE, T1.plusSeconds(3));
    }

    private void finishedAccess(String id, AccessResult result, Instant started, Instant finished) {
      var run = new RunId(id);
      adapters.runs().start(run, SOURCE, started);
      adapters
          .runs()
          .finish(
              run,
              RunOutcome.succeeded(0),
              CollectionCoverage.INCREMENTAL,
              finished,
              0,
              result,
              Optional.of(finished));
    }

    void givenHiddenDocuments() {
      PostgresTestAdapters.unitMaintenance(PostgresSupport.dataSource())
          .upsert(
              SpaceId.LEGACY,
              List.of(
                  ContextUnits.unit("u1").source(SOURCE.value()).inDocument("missing").build(),
                  ContextUnits.unit("u2").source(SOURCE.value()).inDocument("failed").build(),
                  ContextUnits.unit("u3").source(SOURCE.value()).inDocument("unknown").build()));
      new PostgresIndexConfiguration()
          .newAclStore(PostgresSupport.dataSource())
          .put(
              SpaceId.LEGACY,
              List.of(
                  new DocumentAcl("failed", Status.FAILED, Set.of(), T0),
                  new DocumentAcl("unknown", Status.UNKNOWN, Set.of(), T0)));
    }

    void givenHiddenDocumentsWithPrunedOwner() {
      givenHiddenDocuments();
      var jdbc = JdbcClient.create(PostgresSupport.dataSource());
      PostgresTestAdapters.unitMaintenance(PostgresSupport.dataSource())
          .deleteByDocument(SpaceId.LEGACY, SOURCE.value(), "missing", List.of());
      new PostgresIndexConfiguration()
          .newDocumentOwnership(PostgresSupport.dataSource())
          .claim(SpaceId.LEGACY, SOURCE.value(), "missing");
      if (jdbc.sql("SELECT count(*) FROM document_spaces WHERE document_id = 'missing'")
              .query(Integer.class)
              .single()
          != 1) throw new IllegalStateException("given 준비 실패: 정리된 문서의 소유 행 없음");
    }

    void givenIncompleteAccess() {
      var run = new RunId("body-success");
      adapters.runs().start(run, SOURCE, T0);
      adapters
          .runs()
          .finish(
              run,
              RunOutcome.succeeded(0),
              CollectionCoverage.INCREMENTAL,
              T1,
              0,
              AccessResult.INCOMPLETE,
              Optional.of(T1));
      for (String id : List.of("file:1", "file:2", "file:3")) {
        adapters.rawItems().apply(SOURCE, RawItems.item(id).resourceType("gdrive.file").upsert());
        adapters
            .rawAcls()
            .observe(
                SOURCE,
                id,
                RawAcl.observed(id.equals("file:3") ? Status.FAILED : Status.UNKNOWN, Set.of(), T0),
                T0);
      }
      PostgresTestAdapters.unitMaintenance(PostgresSupport.dataSource())
          .upsert(
              new SpaceId("legacy"),
              List.of(ContextUnits.unit("u1").source(SOURCE.value()).inDocument("doc-1").build()));
    }
  }
}
