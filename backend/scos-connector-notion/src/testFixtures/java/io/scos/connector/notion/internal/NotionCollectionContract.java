package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.RunStore;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.TsRawViews.RejectionView;
import io.scos.collection.internal.TsRawViews.ReportView;
import io.scos.connector.notion.NotionAdapter;
import io.scos.connector.notion.NotionAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionMode;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Notion roots 수집을 실제 런타임(점유·페이지 커밋·완료 정리)으로 돌려 v4 계약과 의도된 TS 편차를 고정한다 — 메모리 포트(TESTING §3.6 5층,
 * {@code MemoryNotionCollectionTest})와 PostgreSQL(6층, {@code NotionCollectionTest})이 같은 사례를
 * 지킨다(§3.6 "5·6층을 계약 하나로"). 삭제 계약(F6 T6.4)을 본다: 휴지통 객체는 tombstone, 거부가 남은 run은 정리하지 않고 거부가 없는 사이클
 * run만 보지 못한 원본을 정리한다(complete_snapshot sweep), 중간 재개 run은 정리하지 않는다, 공유되지 않은 루트는 run 실패다. 구현 차이는
 * {@link #newRuntime}에만 있다.
 */
@DisplayName("Notion roots 수집은 실제 런타임에서 v4 재조정 계약을 지킨다(F6 T6.4·F17-D34)")
public abstract class NotionCollectionContract {
  /** 구현별 저장소 위의 실제 수집 런타임과 그 저장소(소스 등록·원본 관찰용). */
  public record Assembled(
      CollectionRuntime runtime,
      SourceInstanceStore sources,
      RawItemStore rawItems,
      RunStore runs,
      CheckpointPort checkpoints,
      SnapshotGuardStore guards) {}

  /** 가짜 Notion을 가리키는 어댑터와 공급자 호출 실행기로 이 구현의 런타임을 조립한다. 저장소는 비어 있어야 한다. */
  protected abstract Assembled newRuntime(
      NotionAdapter adapter, MutableClock clock, ProviderCallExecutor calls);

  /** 장애 run의 시도 timeout(TS 빠른 정책 — 응답하지 않는 요청을 끊는다). */
  static final Duration FAST_TIMEOUT = NotionScenario.FAST_TIMEOUT;

  // 같은 서버로 세 번의 전체 재조정을 실행한다. 안정성 재조회가 추가된 실측 요청량을 수용한다.
  static final int MAX_FULL_CYCLES = 3;

  private final MutableClock clock = new MutableClock(NotionScenario.NOW);
  private final JsonNode reports = NotionFixtures.read("expected-reconciliation-reports.json");
  private final JsonNode roots = NotionFixtures.read("expected-raw-items.json");
  private final JsonNode resume = NotionFixtures.read("expected-resume.json");
  private final JsonNode failures = NotionFixtures.read("expected-failures.json");
  private final List<Duration> waits = new CopyOnWriteArrayList<>();
  private FakeNotionServer server;
  private Assembled runtime;

  @BeforeEach
  void setUpContract() {
    try {
      server =
          new FakeNotionServer(
              NotionFixtures.dataset(), FakeNotionServer.REQUEST_BUDGET * MAX_FULL_CYCLES);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assemble(server.baseUrl(), ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT);
  }

  /** 이 origin·시도 timeout으로 런타임을 (다시) 조립하고 roots 소스를 등록한다. 재시도 대기는 기다리지 않고 기록한다. */
  void assemble(String baseUrl, Duration requestTimeout) {
    ProviderCallExecutor calls = NotionAssembly.recordingExecutor(requestTimeout, clock, waits);
    runtime =
        newRuntime(
            NotionAssembly.adapter(baseUrl, server.token(), clock, requestTimeout), clock, calls);
    givenConfig("roots");
  }

  @AfterEach
  void tearDownContract() {
    server.close();
  }

  void givenConfig(String name) {
    runtime
        .sources()
        .save(NotionAssembly.instance(NotionFixtures.config(name), NotionScenario.NOW));
  }

  CollectionRuntime.Report collect() {
    return runtime
        .runtime()
        .collect(NotionAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  CollectionRuntime.Report snapshot() {
    return runtime
        .runtime()
        .collect(NotionAssembly.SOURCE, CollectionMode.SNAPSHOT, CancellationToken.none());
  }

  /** 다음 상황의 앞 단계: 수집 한 번이 성공으로 끝났다(거부 포함 — 아니면 given 준비 실패). */
  CollectionRuntime.Report givenCollected() {
    CollectionRuntime.Report report = collect();
    if (!report.status().isSuccess())
      throw new IllegalStateException("given 준비 실패: 앞 수집이 성공해야 한다 — " + report.status());
    return report;
  }

  /** 재개의 앞 단계: 행 둘째 페이지에서 끊긴 run 하나를 남긴다(그 run이 성공하면 given 준비 실패). */
  CollectionRuntime.Report givenCrashedRun() {
    server.crashAt(NotionFixtures.reconciliationRequest(resume.get("crashAt")));
    CollectionRuntime.Report crashed = collect();
    if (crashed.status().isSuccess())
      throw new IllegalStateException("given 준비 실패: 행 둘째 페이지에서 끊긴 run이어야 한다 — " + crashed);
    return crashed;
  }

  /** 운영자 축소 허용(`source accept-shrink`)을 남긴다 — 다음 완전 스냅샷 정리 한 번은 축소 보호(ADR-0028)를 거치지 않는다. */
  void givenShrinkAccepted() {
    runtime.guards().acceptShrink(NotionAssembly.SOURCE, clock.instant());
  }

  /** TS 장애 run 하나(expected-failures.json cases의 label). */
  JsonNode failure(String label) {
    for (JsonNode c : failures.get("cases")) if (c.get("label").asString().equals(label)) return c;
    throw new IllegalArgumentException(label);
  }

  /** 운영 Notion run 실패의 주원인 넷(F6-report §2). */
  static Stream<String> operationalFailures() {
    return Stream.of("blocks 조회 시간 초과", "전송 실패", "JSON 아닌 게이트웨이 오류", "internal_server_error 소진");
  }

  List<String> activeIds() {
    List<String> out = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    do {
      RawItemStore.Page page = runtime.rawItems().listActive(NotionAssembly.SOURCE, 100, cursor);
      page.items().forEach(i -> out.add(i.externalId()));
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  /** 지금의 tombstone 수와, 기대한 id 가운데 tombstone인 것. */
  TombstoneView tombstones(List<String> expected) {
    return new TombstoneView(
        runtime.rawItems().tombstoneCount(NotionAssembly.SOURCE),
        expected.stream()
            .filter(
                id ->
                    runtime.rawItems().get(NotionAssembly.SOURCE, id).orElseThrow()
                        instanceof RawRecord.Deleted)
            .toList());
  }

  record TombstoneView(int count, List<String> ids) {
    static TombstoneView expected(JsonNode run) {
      List<String> ids = strings(run.get("tombstones"));
      return new TombstoneView(ids.size(), ids);
    }
  }

  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(v -> out.add(v.asString()));
    return out;
  }

  static List<String> activeIdsOf(JsonNode run) {
    if (run.has("activeExternalIds")) return strings(run.get("activeExternalIds"));
    List<String> out = new ArrayList<>();
    run.get("items").forEach(i -> out.add(i.get("key").get("externalId").asString()));
    return out;
  }

  private void givenReconciliationRoot() {
    runtime
        .sources()
        .save(NotionAssembly.instance("{\"rootPageIds\":[\"root-retro\"]}", clock.instant()));
    var empty =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("has_more", false)
            .putNull("next_cursor");
    empty.putArray("results");
    server.respond(
        "GET",
        "/v1/blocks/root-retro/children",
        new Response(
            HttpURLConnection.HTTP_OK, empty.deepCopy().put("type", "block").toString(), Map.of()));
    server.respond(
        "POST",
        "/v1/search",
        new Response(
            HttpURLConnection.HTTP_OK,
            empty.deepCopy().put("type", "page_or_data_source").toString(),
            Map.of()));
  }

  @Test
  @DisplayName("roots 실제 런타임은 같은 run의 부재 검증을 커밋한다")
  void rootsRuntimeCommitsAbsenceVerification() {
    // given
    givenReconciliationRoot();
    // when
    var report = collect();
    // then
    assertThat(runtime.runs().absenceRequirement(report.runId())).isPresent();
    assertThat(runtime.runs().absenceValidation(report.runId())).isPresent();
  }

  @Test
  @DisplayName("roots 실제 런타임은 v4 재조정 확인점을 커밋한다")
  void rootsRuntimeCommitsReconciliationCheckpoint() {
    // when
    collect();
    // then
    assertThat(
            runtime
                .checkpoints()
                .read(NotionAssembly.SOURCE)
                .checkpoint()
                .orElseThrow()
                .schemaVersion())
        .isEqualTo(NotionReconciliationState.SCHEMA_VERSION);
  }

  @Nested
  @DisplayName("처음 수집하면")
  class FirstCollection {
    @Test
    @DisplayName("v4 재조정 보고 전체를 고정한다(TS 대비 F17-D34 편차)")
    void reportMatchesV4() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(reports.get("first")));
    }

    @Test
    @DisplayName("active 원본이 TS와 같다")
    void activeItemsMatchTs() {
      // when
      collect();

      // then
      assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(activeIdsOf(roots));
    }

    @Test
    @DisplayName("저장된 거부가 TS와 같다(위치 원문까지)")
    void storedRejectionsMatchTs() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(runtime.runs().rejections(report.runId()).stream().map(RejectionView::of))
          .containsExactlyElementsOf(NotionTsRuns.TsRun.of(roots).rejections());
    }

    /** 저장소가 값 JSON을 다시 쓸 수 있다(PostgreSQL JSONB는 키 순서를 남기지 않는다) — 값으로 비교한다. */
    @Test
    @DisplayName("v4 확인점은 끝난 전체 큐를 다시 시작하지 않는다")
    void storedCheckpointMatchesTs() {
      // when
      collect();

      // then
      Checkpoint stored =
          runtime.checkpoints().read(NotionAssembly.SOURCE).checkpoint().orElseThrow();
      assertThat(stored.schemaVersion()).isEqualTo(NotionReconciliationState.SCHEMA_VERSION);
      assertThat(NotionFixtures.JSON.readTree(stored.valueJson()).has("traversal")).isFalse();
    }

    @Test
    @DisplayName("휴지통의 페이지·블록·database는 tombstone이 된다")
    void trashedObjectsAreTombstoned() {
      // when
      collect();

      // then
      assertThat(tombstones(strings(roots.get("tombstones"))))
          .isEqualTo(TombstoneView.expected(roots));
    }
  }

  @Nested
  @DisplayName("블록·행이 사라졌지만 거부가 남은 사이클을 다시 수집하면")
  class AfterEditWithRejection {
    final JsonNode ts = roots.get("secondRun");

    @BeforeEach
    void givenEditedAfterFirstCollection() {
      givenCollected();
      server.phase(FakeNotionServer.Phase.EDITED);
    }

    @Test
    @DisplayName("v4 재조정 보고 전체를 고정한다(TS 대비 F17-D34 편차)")
    void reportMatchesV4() {
      // when
      CollectionRuntime.Report report = snapshot();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(reports.get("edited")));
    }

    @Test
    @DisplayName("보지 못한 원본을 정리하지 않는다")
    void doesNotSweep() {
      // when
      snapshot();

      // then
      assertThat(runtime.rawItems().get(NotionAssembly.SOURCE, "row-runbook"))
          .hasValueSatisfying(raw -> assertThat(raw).isInstanceOf(RawRecord.Active.class));
    }
  }

  @Nested
  @DisplayName("거부가 사라진 사이클을 다시 수집하면")
  class AfterShare {
    final JsonNode ts = roots.get("thirdRun");

    @BeforeEach
    void givenSharedAfterEdit() {
      givenCollected();
      server.phase(FakeNotionServer.Phase.EDITED);
      snapshot();
      server.phase(FakeNotionServer.Phase.SHARED);
    }

    @Test
    @DisplayName("공유 후에도 404 부재 후보가 남으면 전체 정리 보류 사유를 보고한다")
    void reportsDeferredAbsenceAfterShare() {
      var report = snapshot();
      // then
      assertThat(report.errorKind()).contains("absence_verification_deferred");
    }

    @Test
    @DisplayName("v4 재조정 보고 전체를 고정한다(TS 대비 F17-D34 편차)")
    void reportMatchesV4() {
      // when
      CollectionRuntime.Report report = snapshot();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(reports.get("shared")));
    }

    @Test
    @DisplayName("완료한 페이지 소유 본문의 사라진 블록은 부분 정리한다")
    void completedBodyRemovesItsMissingBlock() {
      // when
      snapshot();
      // then
      assertThat(runtime.rawItems().get(NotionAssembly.SOURCE, "block:blk-retro-done"))
          .hasValueSatisfying(raw -> assertThat(raw).isInstanceOf(RawRecord.Deleted.class));
    }

    @Test
    @DisplayName("직접 조회 404인 행은 전체 부재로 지우지 않는다")
    void unknownRowIsPreserved() {
      // when
      snapshot();
      // then
      assertThat(runtime.rawItems().get(NotionAssembly.SOURCE, "row-runbook"))
          .hasValueSatisfying(raw -> assertThat(raw).isInstanceOf(RawRecord.Active.class));
    }

    @Test
    @DisplayName("active 원본이 TS와 같다")
    void activeItemsMatchTs() {
      // when
      snapshot();

      // then
      var expected = new ArrayList<>(activeIdsOf(ts));
      expected.addAll(List.of("row-runbook", "block:blk-row-runbook"));
      assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(expected);
    }
  }

  @Nested
  @DisplayName("행 둘째 페이지에서 중단되면")
  class Crashed {
    final JsonNode first = resume.get("firstRun");

    @BeforeEach
    void givenCrashAtSecondRowPage() {
      server.crashAt(NotionFixtures.reconciliationRequest(resume.get("crashAt")));
    }

    @Test
    @DisplayName("v4 재조정 보고 전체를 고정한다(TS 대비 F17-D34 편차)")
    void reportMatchesV4() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(reports.get("crashed")));
    }

    @Test
    @DisplayName("run의 errorKind는 TS와 같다")
    void crashedRunErrorKindMatchesTs() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorKind()).contains(first.get("run").get("errorKind").asString());
    }
  }

  @Nested
  @DisplayName("행 둘째 페이지에서 중단된 뒤 재개하면")
  class ResumedAfterCrash {
    final JsonNode second = resume.get("secondRun");

    @BeforeEach
    void givenCrashedRunThenRecovered() {
      givenCrashedRun();
      server.clearCrash();
    }

    @Test
    @DisplayName("v4 재조정 보고 전체를 고정한다(TS 대비 F17-D34 편차)")
    void reportMatchesV4() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(reports.get("resumed")));
    }

    @Test
    @DisplayName("재개한 run은 앞 run이 본 원본을 정리하지 않는다")
    void resumedRunDoesNotSweep() {
      // when
      collect();

      // then
      assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(activeIdsOf(second));
    }

    /** 이 fixture의 재개 run은 앞 run의 원본을 하나도 관측하지 않아 축소 보호도 정리를 막는다 — 허용으로 보호를 비켜 coverage만 남긴다. */
    @Nested
    @DisplayName("운영자가 축소를 허용해 두었어도")
    class ShrinkAccepted {
      @BeforeEach
      void givenAccepted() {
        givenShrinkAccepted();
      }

      @Test
      @DisplayName("재개한 run은 앞 run이 본 원본을 정리하지 않는다")
      void resumedRunStillDoesNotSweep() {
        // when
        collect();

        // then
        assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(activeIdsOf(second));
      }
    }
  }

  @Nested
  @DisplayName("검색 모드로 수집하면")
  class SearchMode {
    final JsonNode ts = NotionFixtures.read("expected-modes.json").get("search");

    @BeforeEach
    void givenSearchConfig() {
      givenConfig("search");
    }

    @Test
    @DisplayName("run 보고가 TS와 같다")
    void reportMatchesTs() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(ts.get("report")));
    }

    @Test
    @DisplayName("휴지통 단계의 페이지만 tombstone이 된다")
    void onlyTrashedPagesAreTombstoned() {
      // when
      collect();

      // then
      assertThat(tombstones(strings(ts.get("tombstones")))).isEqualTo(TombstoneView.expected(ts));
    }
  }

  /**
   * 운영 Notion run 실패의 주원인 넷(F6-report §2 — 시간 초과·fetch failed·JSON 아닌 응답·5xx 소진)을 실제 런타임으로 돌린다. TS
   * 장애 run (expected-failures.json)의 보고·errorKind·재시도 수와 같다. 재시도 수는 run 전체의 재시도 대기 수다 — 실패한 호출의
   * 소진(4)과 앞선 root-retro rate_limited 재시도(1, 전송 실패 run에는 없다)를 합친다.
   */
  @Nested
  @DisplayName("운영 실패 원인으로 run이 끝나면")
  class OperationalFailure {
    /** TS 장애 run의 장애 각본을 걸고, 전송 실패면 닫힌 포트로 빠른 timeout 런타임을 다시 조립한다. */
    void givenOperationalFailure(String label) {
      JsonNode failure = failure(label);
      failure
          .get("faults")
          .forEach(fault -> server.fault(NotionFixtures.reconciliationRequest(fault)));
      assemble(
          failure.get("transportDown").asBoolean() ? NotionAssembly.closedPort() : server.baseUrl(),
          FAST_TIMEOUT);
    }

    int failedCallAttempts(String label) {
      var failure = failure(label);
      if (failure.path("transportDown").asBoolean()) return waits.size() + 1;
      String path = failure.path("faults").get(0).path("path").asString();
      return (int)
          server.requests().stream().filter(request -> request.path().equals(path)).count();
    }

    int expectedFailureAttempts(String label) {
      return label.equals("JSON 아닌 게이트웨이 오류")
          ? 1
          : ProviderCallPolicy.defaults(() -> 0.0).maxAttempts();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCollectionContract#operationalFailures")
    @DisplayName("v4 재조정 보고 전체를 고정한다(TS 대비 F17-D34 편차)")
    void reportMatchesV4(String label) {
      // given
      givenOperationalFailure(label);

      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report))
          .isEqualTo(ReportView.expected(reports.get("failure:" + label)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCollectionContract#operationalFailures")
    @DisplayName("run errorKind가 TS 저장값과 같다")
    void errorKindMatchesTs(String label) {
      // given
      givenOperationalFailure(label);

      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorKind())
          .contains(failure(label).get("run").get("errorKind").asString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCollectionContract#operationalFailures")
    @DisplayName("실패한 호출은 분류된 정책의 시도 한도만큼 재시도한다")
    void retriesLikeTs(String label) {
      // given
      givenOperationalFailure(label);

      // when
      collect();

      // then
      assertThat(failedCallAttempts(label)).isEqualTo(expectedFailureAttempts(label));
    }
  }

  /**
   * run 오류 요약(공급자 실패는 failure.message, 그 밖은 예외 메시지, 공용 fad8ffe)이 TS 저장값과 같은 경우. 시간 초과·전송 실패는 HTTP
   * 클라이언트의 메시지라 TS("Provider call timed out: …"·"fetch failed")와 다르다(CONTRACTS F17-D5).
   */
  @Nested
  @DisplayName("Notion API 오류나 프로토콜 위반으로 run이 끝나면")
  class RunSummary {
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
          "JSON 아닌 게이트웨이 오류|roots",
          "internal_server_error 소진|roots",
          "루트 object_not_found|missingRoot",
          "다른 id의 페이지|roots",
          "휴지통 행을 돌려준 data source 조회|roots"
        })
    @DisplayName("run 오류 요약이 TS 저장값과 같다")
    void summaryMatchesTs(String label, String config) {
      // given: 빠른 timeout으로 다시 조립한 런타임의 이 설정 소스에 TS 장애 각본
      failure(label)
          .get("faults")
          .forEach(fault -> server.fault(NotionFixtures.reconciliationRequest(fault)));
      assemble(server.baseUrl(), FAST_TIMEOUT);
      givenConfig(config);

      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorSummary())
          .contains(failure(label).get("run").get("errorSummary").asString());
    }
  }

  /** TS처럼 coverage에서 던진다 — 런타임은 run을 남기지 않고 점유를 풀어 전파한다(CONTRACTS F17-D1, 런타임 560895e). */
  @Nested
  @DisplayName("설정한 루트를 바꾼 뒤 저장된 체크포인트로 수집하면")
  class ChangedRoots {
    CollectionRuntime.Report crashed;

    @BeforeEach
    void givenCrashedThenRootsChanged() {
      crashed = givenCrashedRun();
      server.clearCrash();
      runtime
          .sources()
          .save(NotionAssembly.instance("{\"rootPageIds\":[\"root-other\"]}", NotionScenario.NOW));
    }

    /** 바뀐 루트로 수집이 실패했음을 확보한다(실패하지 않거나 다른 이유면 given 준비 실패). */
    void collectFailingOnChangedRoots() {
      Throwable thrown = catchThrowable(NotionCollectionContract.this::collect);
      if (!(thrown instanceof NotionProtocolException))
        throw new IllegalStateException("given 준비 실패: 바뀐 루트로 실패해야 한다 — " + thrown);
    }

    @Test
    @DisplayName("run을 남기지 않는다(마지막 run은 중단된 run이다)")
    void leavesNoRun() {
      // when
      collectFailingOnChangedRoots();

      // then
      assertThat(runtime.runs().latest(NotionAssembly.SOURCE).orElseThrow().id())
          .isEqualTo(crashed.runId());
    }

    @Test
    @DisplayName("TS 메시지로 수집이 실패한다")
    void failsWithTsMessage() {
      // when
      Throwable thrown = catchThrowable(NotionCollectionContract.this::collect);

      // then
      assertThat(thrown)
          .isInstanceOf(NotionProtocolException.class)
          .hasMessage("Notion source checkpoint does not match configured roots");
    }

    @Test
    @DisplayName("점유를 남기지 않아 원래 루트로 되돌리면 바로 다시 수집한다")
    void leavesNoClaim() {
      // given
      collectFailingOnChangedRoots();
      givenConfig("roots");

      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.status()).isEqualTo(RunStatus.SUCCEEDED_WITH_REJECTIONS);
    }
  }

  @Nested
  @DisplayName("Notion이 루트나 페이지를 잘못 돌려주면")
  class FailedRun {
    @Test
    @DisplayName("공유되지 않은 루트는 permission_denied로 실패한다")
    void unsharedRootFails() {
      // given
      givenConfig("missingRoot");
      failure("루트 object_not_found").get("faults").forEach(server::fault);

      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorKind()).contains("permission_denied");
    }

    @Test
    @DisplayName("요청과 다른 id의 페이지는 Error다")
    void wrongPageIsTsError() {
      // given
      givenConfig("roots");
      failure("다른 id의 페이지").get("faults").forEach(server::fault);

      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorKind()).contains("Error");
    }
  }
}
