package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.TsRawViews.ReportView;
import io.scos.connector.googledrive.GoogleDriveAdapter;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.support.FakeHttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Drive 수집을 실제 런타임(점유·페이지 커밋·완료 기록)으로 돌린 결과가 TS와 같다 — 메모리 포트(TESTING §3.6 5층, {@code
 * MemoryGoogleDriveCollectionTest})와 PostgreSQL(6층, {@code GoogleDriveCollectionTest})이 같은 사례를
 * 지킨다(§5 계약 형태). 첫 수집, 그 위의 변경 피드 run(접근 상실·휴지통·드라이브 회수 → tombstone), 시트 범위 요청에서 끊긴 run과 그 체크포인트에서의
 * 재수집을 TS 런타임 기대(expected-raw-items·second·resume.json)와 비교한다. 구현 차이는 {@link #newRuntime}에만 있다.
 */
@DisplayName("Drive 수집은 실제 런타임에서 TS와 같이 남기고 지운다(F7, §3.6 5·6층)")
public abstract class GoogleDriveCollectionContract {
  static final Instant NOW = GoogleDriveScenario.NOW;

  /** TS가 일반 Error로 던지는 경우의 run errorKind(CONTRACTS "run errorKind 매핑"). */
  static final String TS_ERROR = "Error";

  static final String ACCOUNT_MISMATCH =
      "Google OAuth account does not match the configured Drive account";

  /** 구현별 저장소 위의 실제 수집 런타임과 그 저장소(소스 등록·원본·체크포인트 관찰용). */
  public record Assembled(
      CollectionRuntime runtime,
      SourceInstanceStore sources,
      RawItemStore rawItems,
      CheckpointPort checkpoints) {}

  /** 가짜 Google을 가리키는 어댑터로 이 구현의 런타임을 조립한다. 저장소는 비어 있어야 한다. */
  protected abstract Assembled newRuntime(GoogleDriveAdapter adapter, MutableClock clock);

  private final MutableClock clock = new MutableClock(NOW);
  private FakeGoogleServer server;
  private Assembled runtime;

  @BeforeEach
  void setUpContract() {
    try {
      server = new FakeGoogleServer(GoogleDriveFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    runtime =
        newRuntime(
            GoogleDriveAssembly.adapter(
                server.baseUrl(), GoogleDriveFixtures.credentialJson(), clock),
            clock);
    runtime
        .sources()
        .save(GoogleDriveAssembly.instance(GoogleDriveAssembly.SYNTHETIC_ACCOUNT, NOW));
  }

  @AfterEach
  void tearDownContract() {
    server.close();
  }

  CollectionRuntime.Report collect() {
    return runtime
        .runtime()
        .collect(GoogleDriveAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  /** active 원본마다 sourceVersion. */
  Map<String, String> activeVersions() {
    Map<String, String> out = new LinkedHashMap<>();
    Optional<String> cursor = Optional.empty();
    do {
      RawItemStore.Page page =
          runtime.rawItems().listActive(GoogleDriveAssembly.SOURCE, 100, cursor);
      page.items().forEach(i -> out.put(i.externalId(), i.sourceVersion()));
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  /** 이 id들 가운데 tombstone인 것. */
  List<String> tombstonesAmong(List<String> ids) {
    return ids.stream()
        .filter(
            id ->
                runtime.rawItems().get(GoogleDriveAssembly.SOURCE, id).orElse(null)
                    instanceof RawRecord.Deleted)
        .toList();
  }

  int tombstoneCount() {
    return runtime.rawItems().tombstoneCount(GoogleDriveAssembly.SOURCE);
  }

  JsonNode checkpoint() {
    return GoogleDriveViews.checkpointJson(
        runtime.checkpoints().read(GoogleDriveAssembly.SOURCE).checkpoint().orElseThrow());
  }

  @Nested
  @DisplayName("처음 수집하면")
  class FirstCollection {
    final TsRuntimeRun ts = TsRuntimeRun.of(GoogleDriveFixtures.read("expected-raw-items.json"));

    @Test
    @DisplayName("run 보고가 TS와 같다")
    void reportMatchesTs() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ts.report());
    }

    @Test
    @DisplayName("active 원본과 sourceVersion이 TS와 같다")
    void activeItemsMatchTs() {
      // when
      collect();

      // then
      assertThat(activeVersions()).containsExactlyInAnyOrderEntriesOf(ts.activeVersions());
    }

    @Test
    @DisplayName("수집한 적 없는 파일의 접근 상실도 tombstone으로 남는다")
    void tombstonesMatchTs() {
      // when
      collect();

      // then
      assertThat(tombstonesAmong(ts.tombstones())).containsExactlyElementsOf(ts.tombstones());
    }

    @Test
    @DisplayName("tombstone 수가 TS와 같다")
    void tombstoneCountMatchesTs() {
      // when
      collect();

      // then
      assertThat(tombstoneCount()).isEqualTo(ts.tombstones().size());
    }

    @Test
    @DisplayName("저장된 체크포인트가 TS와 같다")
    void checkpointMatchesTs() {
      // when
      collect();

      // then
      assertThat(checkpoint()).isEqualTo(ts.checkpoint());
    }
  }

  @Nested
  @DisplayName("변경 피드를 받아 다시 수집하면")
  class ChangeFeedRun {
    final TsRuntimeRun ts = TsRuntimeRun.of(GoogleDriveFixtures.read("expected-second.json"));

    /** 첫 수집이 TS 첫 수집처럼 끝까지 돈 뒤(succeeded_with_rejections) 변경 피드 응답으로 바꾼다. */
    @BeforeEach
    void givenFirstCollectionThenChanges() {
      CollectionRuntime.Report first = collect();
      if (first.status() != RunStatus.SUCCEEDED_WITH_REJECTIONS)
        throw new IllegalStateException(
            "given 준비 실패: 첫 수집이 succeeded_with_rejections로 끝나지 않았다 — " + first);
      server.useSecondRunRoutes();
    }

    @Test
    @DisplayName("run 보고가 TS와 같다")
    void reportMatchesTs() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ts.report());
    }

    @Test
    @DisplayName("active 원본과 sourceVersion이 TS와 같다")
    void activeItemsMatchTs() {
      // when
      collect();

      // then
      assertThat(activeVersions()).containsExactlyInAnyOrderEntriesOf(ts.activeVersions());
    }

    @Test
    @DisplayName("접근 상실·휴지통·드라이브 회수로 지운 원본은 tombstone이다")
    void tombstonesMatchTs() {
      // when
      collect();

      // then
      assertThat(tombstonesAmong(ts.tombstones())).containsExactlyElementsOf(ts.tombstones());
    }

    @Test
    @DisplayName("tombstone 수가 TS와 같다")
    void tombstoneCountMatchesTs() {
      // when
      collect();

      // then
      assertThat(tombstoneCount()).isEqualTo(ts.tombstones().size());
    }

    @Test
    @DisplayName("저장된 체크포인트가 TS와 같다")
    void checkpointMatchesTs() {
      // when
      collect();

      // then
      assertThat(checkpoint()).isEqualTo(ts.checkpoint());
    }
  }

  /** TS 생성기의 중단 지점: 셋째 시트 범위 요청에 401(비재시도)로 답한다. */
  void givenCrashAtThirdRange(JsonNode crash) {
    Map<String, String> params = new LinkedHashMap<>();
    crash.get("params").properties().forEach(e -> params.put(e.getKey(), e.getValue().asString()));
    server.crashAt(crash.get("path").asString(), params);
  }

  @Nested
  @DisplayName("시트 범위 요청에서 끊기면")
  class Crashed {
    final JsonNode root = GoogleDriveFixtures.read("expected-resume.json");

    @BeforeEach
    void givenCrashAtThirdRange() {
      GoogleDriveCollectionContract.this.givenCrashAtThirdRange(root.get("crashAt"));
    }

    @Test
    @DisplayName("끊긴 run 보고가 TS와 같다")
    void crashedReportMatchesTs() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report))
          .isEqualTo(ReportView.expected(root.get("firstRun").get("report")));
    }
  }

  @Nested
  @DisplayName("시트 범위 요청에서 끊긴 뒤 다시 수집하면")
  class AfterCrash {
    final JsonNode root = GoogleDriveFixtures.read("expected-resume.json");

    /** 셋째 범위에서 끊겨 첫 run이 partially_succeeded로 끝난 뒤 장애를 거둔다. 끊기지 않았으면 멈춘다. */
    @BeforeEach
    void givenCrashedThenRecovered() {
      givenCrashAtThirdRange(root.get("crashAt"));
      CollectionRuntime.Report crashed = collect();
      if (crashed.status() != RunStatus.PARTIALLY_SUCCEEDED)
        throw new IllegalStateException(
            "given 준비 실패: 첫 run이 범위 요청에서 끊기지 않았다(partially_succeeded 아님) — " + crashed);
      server.recover();
    }

    @Test
    @DisplayName("다시 수집한 run 보고가 TS와 같다")
    void resumedReportMatchesTs() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report))
          .isEqualTo(ReportView.expected(root.get("secondRun").get("report")));
    }

    @Test
    @DisplayName("다시 수집한 뒤 active 원본이 TS와 같다")
    void activeItemsMatchTs() {
      // when
      collect();

      // then
      assertThat(activeVersions())
          .containsExactlyInAnyOrderEntriesOf(TsRuntimeRun.versions(root.get("items")));
    }
  }

  @Nested
  @DisplayName("OAuth 계정이 설정과 다르면")
  class AccountMismatch {
    @BeforeEach
    void givenOtherAccount() {
      server.respond(
          "/drive/v3/about",
          FakeHttpServer.ok(
              "{\"user\":{\"emailAddress\":\"other@example.test\",\"permissionId\":\"x\"}}"));
    }

    @Test
    @DisplayName("run은 failed다")
    void runFails() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("run errorKind는 TS처럼 Error다")
    void storesTsErrorKind() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorKind()).contains(TS_ERROR);
    }

    @Test
    @DisplayName("run 오류 요약은 TS 메시지 그대로다")
    void storesTsMessage() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorSummary()).contains(ACCOUNT_MISMATCH);
    }

    @Test
    @DisplayName("파일을 읽지 않는다")
    void readsNoFile() {
      // when
      collect();

      // then
      assertThat(activeVersions()).isEmpty();
    }
  }

  /**
   * 실패한 run 뒤의 점유: run이 끝날 때 점유를 놓으므로 시계가 흐르지 않아도(점유 TTL 전) 바로 다음 수집이 된다. 첫 페이지 전에 끝난 실패(토큰 갱신 거절)라
   * 다음 수집은 첫 수집과 같다.
   */
  @Nested
  @DisplayName("토큰 갱신이 거절된 run 뒤 장애가 지나 바로 다시 수집하면")
  class AfterFailedRun {
    final TsRuntimeRun ts = TsRuntimeRun.of(GoogleDriveFixtures.read("expected-raw-items.json"));

    @BeforeEach
    void givenFailedRunThenRecovered() {
      server.respondAll(failureRun("invalid_grant").get("overrides"));
      CollectionRuntime.Report failed = collect();
      if (failed.status() != RunStatus.FAILED)
        throw new IllegalStateException("given 준비 실패: 토큰 갱신 거절로 실패한 run이어야 한다 — " + failed);
      server.clearOverrides();
    }

    @Test
    @DisplayName("run 보고가 TS 첫 수집과 같다")
    void reportMatchesFirstCollection() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ts.report());
    }
  }

  /** TS 운영 실패 각본 가운데 이름에 이 조각이 든 것. */
  static JsonNode failureRun(String labelPart) {
    return StreamSupport.stream(
            GoogleDriveFixtures.read("expected-failures.json").get("runs").spliterator(), false)
        .filter(r -> r.get("label").asString().contains(labelPart))
        .findFirst()
        .orElseThrow();
  }

  static Stream<Arguments> failureRuns() {
    return StreamSupport.stream(
            GoogleDriveFixtures.read("expected-failures.json").get("runs").spliterator(), false)
        .map(r -> Arguments.of(r.get("label").asString(), r));
  }

  /** 운영 Drive 실패(F7-report "장애")의 유형: 첫 페이지 전 공급자 실패(재시도 소진·인증·계정)와 run 중간 다운로드 실패. */
  @Nested
  @DisplayName("운영 실패 유형을 재현하면")
  class OperationalFailures {
    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.scos.connector.googledrive.internal.GoogleDriveCollectionContract#failureRuns")
    @DisplayName("run 보고가 TS와 같다")
    void reportMatchesTs(String label, JsonNode ts) {
      // given
      server.respondAll(ts.get("overrides"));

      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(ts.get("report")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.scos.connector.googledrive.internal.GoogleDriveCollectionContract#failureRuns")
    @DisplayName("run errorKind가 TS와 같다")
    void errorKindMatchesTs(String label, JsonNode ts) {
      // given
      server.respondAll(ts.get("overrides"));

      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorKind()).contains(ts.get("errorKind").asString());
    }

    /**
     * 요약 문구는 TS `classifyRunError`와 같은 규칙이다(CONTRACTS "run errorKind 매핑" 아래 errorSummary) — 바이트로
     * 같다.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.scos.connector.googledrive.internal.GoogleDriveCollectionContract#failureRuns")
    @DisplayName("run 오류 요약이 TS와 같다")
    void errorSummaryMatchesTs(String label, JsonNode ts) {
      // given
      server.respondAll(ts.get("overrides"));

      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorSummary()).contains(ts.get("errorSummary").asString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.scos.connector.googledrive.internal.GoogleDriveCollectionContract#failureRuns")
    @DisplayName("재시도를 포함한 요청 수가 TS와 같다")
    void requestCountMatchesTs(String label, JsonNode ts) {
      // given
      server.respondAll(ts.get("overrides"));

      // when
      collect();

      // then
      assertThat(server.requests()).hasSize(ts.get("requestCount").asInt());
    }
  }

  /** TS 런타임 run 하나의 기대: 보고, 최종 active 원본의 버전, tombstone id, 저장된 체크포인트. */
  record TsRuntimeRun(
      ReportView report,
      Map<String, String> activeVersions,
      List<String> tombstones,
      JsonNode checkpoint) {
    static TsRuntimeRun of(JsonNode root) {
      List<String> tombstones =
          root.get("tombstones").valueStream().map(t -> t.get("externalId").asString()).toList();
      return new TsRuntimeRun(
          ReportView.expected(root.get("report")),
          versions(root.get("items")),
          tombstones,
          root.get("checkpoint").get("checkpoint"));
    }

    static Map<String, String> versions(JsonNode items) {
      Map<String, String> out = new LinkedHashMap<>();
      items.forEach(
          i ->
              out.put(
                  i.get("key").get("externalId").asString(), i.get("sourceVersion").asString()));
      return out;
    }
  }
}
