package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.TsRawViews.ItemView;
import io.scos.connector.googledrive.internal.GoogleDriveScenario.Collection;
import io.scos.connector.googledrive.internal.GoogleDriveViews.ErrorView;
import io.scos.connector.googledrive.internal.GoogleDriveViews.PageView;
import io.scos.connector.googledrive.internal.GoogleDriveViews.RequestView;
import io.scos.connector.googledrive.internal.GoogleDriveViews.TsRun;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import java.time.Duration;
import java.util.List;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * F7 동등성(TESTING §3.6 3층): 가짜 Google(harness/fixtures/google-drive)에서 Java GoogleDriveAdapter가 낸
 * 페이지(변경·거부· 체크포인트)·원본·요청·재시도 대기가 TS GoogleDriveAdapter와 같다. 첫 수집, 변경 피드 run, 시트 범위 중단 뒤 재개, 작은 캡처
 * 상한, 어댑터 단독 실패·체크포인트 사례를 본다. Drive는 작업을 하나씩 하므로 요청 순서까지 같다.
 */
@DisplayName("Drive 어댑터는 TS GoogleDriveAdapter와 같은 페이지·원본·요청을 낸다(F7)")
class GoogleDriveAdapterFixtureTest {
  final GoogleDriveScenario drive = new GoogleDriveScenario();

  @AfterEach
  void tearDown() {
    drive.close();
  }

  static List<String> mutations(List<PageView> pages) {
    return pages.stream().flatMap(p -> p.mutations().stream()).toList();
  }

  @Nested
  @DisplayName("처음부터 수집하면 TS와 같다")
  class FirstCollection {
    final TsRun ts = TsRun.of("expected-raw-items.json");

    @Test
    @DisplayName("페이지마다 같은 원본을 같은 순서로 쓰고 지운다")
    void pagesCarrySameMutations() {
      // when
      Collection java = drive.collect();

      // then
      assertThat(java.pageViews())
          .extracting(PageView::mutations)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::mutations).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 체크포인트를 낸다")
    void pagesCarrySameCheckpoints() {
      // when
      Collection java = drive.collect();

      // then
      assertThat(java.pageViews())
          .extracting(PageView::nextCheckpoint)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::nextCheckpoint).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 거부를 낸다")
    void pagesCarrySameRejections() {
      // when
      Collection java = drive.collect();

      // then
      assertThat(java.pageViews())
          .extracting(PageView::rejections)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::rejections).toList());
    }

    @Test
    @DisplayName("원본 항목의 필드가 같다(버전·시각·메타데이터·본문·blob)")
    void itemsMatch() {
      // when
      Collection java = drive.collect();

      // then
      assertThat(java.activeItems()).containsExactlyInAnyOrderElementsOf(ts.items());
    }

    @Test
    @DisplayName("같은 요청을 같은 순서로 보낸다")
    void sendsSameRequests() {
      // when
      drive.collect();

      // then
      assertThat(drive.requests()).containsExactlyElementsOf(ts.requests());
    }

    @Test
    @DisplayName("재시도마다 같은 시간을 기다린다")
    void waitsSameDelays() {
      // when
      drive.collect();

      // then
      assertThat(drive.waits()).containsExactlyElementsOf(ts.waits());
    }
  }

  @Nested
  @DisplayName("첫 수집의 체크포인트에서 변경 피드를 받아 다시 수집하면 TS와 같다")
  class ChangeFeedRun {
    final TsRun ts = TsRun.of("expected-second.json");
    final Checkpoint afterFirst = TsRun.of("expected-raw-items.json").finalCheckpoint();

    /**
     * 이 run이 쓰고 지우지 않은 원본(expected-second.json 페이지 변경을 손으로 따라 적었다): slides-1은 지운 뒤 다시 쓰여 남고,
     * sheet-1·pdf-1·team-doc-1·sheet-race-1·drive team-1은 지워져 빠진다.
     */
    final List<ItemView> survivingChanges =
        ts.itemsWith(
            "binary:text-1",
            "file:text-1",
            "file:slides-1",
            "binary:new-1",
            "file:new-1",
            "file:doc-1",
            "file:sheet-2",
            "sheet:sheet-2:3:0:0:100:50",
            "sheet:sheet-2:3:100:0:200:50",
            "sheet:sheet-2:3:200:0:250:50",
            "file:folder-1",
            "file:blob-1");

    @BeforeEach
    void givenSecondRunResponses() {
      drive.givenSecondRunResponses();
    }

    @Test
    @DisplayName("페이지마다 같은 원본을 같은 순서로 쓰고 지운다")
    void pagesCarrySameMutations() {
      // when
      Collection java = drive.collectFrom(afterFirst);

      // then
      assertThat(java.pageViews())
          .extracting(PageView::mutations)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::mutations).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 체크포인트를 낸다")
    void pagesCarrySameCheckpoints() {
      // when
      Collection java = drive.collectFrom(afterFirst);

      // then
      assertThat(java.pageViews())
          .extracting(PageView::nextCheckpoint)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::nextCheckpoint).toList());
    }

    @Test
    @DisplayName("이 run이 남긴 원본은 TS 변경 피드 run이 남긴 원본과 같다")
    void itemsMatch() {
      // when
      Collection java = drive.collectFrom(afterFirst);

      // then
      assertThat(java.activeItems()).containsExactlyInAnyOrderElementsOf(survivingChanges);
    }

    @Test
    @DisplayName("같은 요청을 같은 순서로 보낸다")
    void sendsSameRequests() {
      // when
      drive.collectFrom(afterFirst);

      // then
      assertThat(drive.requests()).containsExactlyElementsOf(ts.requests());
    }
  }

  @Nested
  @DisplayName("시트 범위 요청에서 중단된 run의 체크포인트에서 재개하면 TS와 같다")
  class ResumeAfterCrash {
    final JsonNode root = GoogleDriveFixtures.read("expected-resume.json");
    final TsRun ts = resumed(root);
    final Checkpoint crashed = crashCheckpoint(root);

    /** 재개 run이 쓴 원본(expected-resume.json 둘째 run 페이지 변경을 손으로 따라 적었다 — 지우는 변경은 없다). */
    final List<ItemView> resumedUpserts =
        ts.itemsWith(
            "sheet:sheet-1:0:100:0:150:100",
            "sheet:sheet-1:0:100:100:150:120",
            "file:sheet-2",
            "binary:pdf-1",
            "file:pdf-1",
            "file:slides-1",
            "binary:text-1",
            "file:text-1",
            "binary:text-2",
            "file:text-2",
            "binary:json-1",
            "file:json-1",
            "file:folder-1",
            "file:shortcut-1",
            "file:form-1",
            "file:large-1",
            "file:restricted-1",
            "file:big-doc-1",
            "binary:big-comments-1",
            "file:big-comments-1",
            "file:big-meta-1",
            "binary:blob-1",
            "file:blob-1",
            "file:huge-doc-1",
            "file:sheet-big-1",
            "sheet:sheet-big-1:3:0:0:30:4",
            "drive:team-1",
            "file:team-doc-1",
            "file:sheet-race-1");

    static TsRun resumed(JsonNode root) {
      ObjectNode second = (ObjectNode) root.get("secondRun").deepCopy();
      second.set("items", root.get("items"));
      return TsRun.of(second);
    }

    static Checkpoint crashCheckpoint(JsonNode root) {
      JsonNode c = root.get("firstRun").get("checkpoint").get("checkpoint");
      return new Checkpoint(c.get("schemaVersion").asInt(), c.get("value").toString());
    }

    @Test
    @DisplayName("남은 작업부터 같은 원본을 같은 순서로 쓴다")
    void pagesCarrySameMutations() {
      // when
      Collection java = drive.collectFrom(crashed);

      // then
      assertThat(java.pageViews())
          .extracting(PageView::mutations)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::mutations).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 체크포인트를 낸다")
    void pagesCarrySameCheckpoints() {
      // when
      Collection java = drive.collectFrom(crashed);

      // then
      assertThat(java.pageViews())
          .extracting(PageView::nextCheckpoint)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::nextCheckpoint).toList());
    }

    @Test
    @DisplayName("이미 확정된 작업은 다시 요청하지 않는다")
    void sendsOnlyRemainingRequests() {
      // when
      drive.collectFrom(crashed);

      // then
      assertThat(drive.requests()).containsExactlyElementsOf(ts.requests());
    }

    @Test
    @DisplayName("재개한 run이 낸 원본은 TS 재개 run이 낸 원본과 같다")
    void itemsMatch() {
      // when
      Collection java = drive.collectFrom(crashed);

      // then
      assertThat(java.activeItems()).containsExactlyInAnyOrderElementsOf(resumedUpserts);
    }
  }

  @Nested
  @DisplayName("원본 캡처 상한이 작으면 TS와 같다")
  class SmallCaptureLimit {
    final JsonNode root = GoogleDriveFixtures.read("expected-limit.json");
    final TsRun ts = TsRun.of(root);

    @BeforeEach
    void givenTsLimit() {
      drive.captureLimit(
          root.get("capture").get("inlineMaxBytes").asInt(),
          root.get("capture").get("maxBytes").asLong());
    }

    @Test
    @DisplayName("페이지마다 같은 원본을 같은 순서로 쓴다")
    void pagesCarrySameMutations() {
      // when
      Collection java = drive.collect();

      // then
      assertThat(java.pageViews())
          .extracting(PageView::mutations)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::mutations).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 거부를 낸다")
    void pagesCarrySameRejections() {
      // when
      Collection java = drive.collect();

      // then
      assertThat(java.pageViews())
          .extracting(PageView::rejections)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::rejections).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 체크포인트를 낸다")
    void pagesCarrySameCheckpoints() {
      // when
      Collection java = drive.collect();

      // then
      assertThat(java.pageViews())
          .extracting(PageView::nextCheckpoint)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::nextCheckpoint).toList());
    }

    @Test
    @DisplayName("원본 항목이 같다(inline·blob 경계 포함)")
    void itemsMatch() {
      // when
      Collection java = drive.collect();

      // then
      assertThat(java.activeItems()).containsExactlyInAnyOrderElementsOf(ts.items());
    }
  }

  static Stream<Arguments> adapterCases() {
    return StreamSupport.stream(GoogleDriveFixtures.cases().get("cases").spliterator(), false)
        .map(c -> Arguments.of(c.get("label").asString(), c));
  }

  /** TS 사례가 기록한 재시도 대기. */
  static List<Duration> expectedWaits(JsonNode c) {
    return StreamSupport.stream(c.get("waits").spliterator(), false)
        .map(w -> Duration.ofMillis(w.asLong()))
        .toList();
  }

  static Optional<Checkpoint> checkpointOf(JsonNode c) {
    JsonNode checkpoint = c.get("checkpoint");
    return checkpoint.isNull()
        ? Optional.empty()
        : Optional.of(
            new Checkpoint(
                checkpoint.get("schemaVersion").asInt(), checkpoint.get("value").toString()));
  }

  @Nested
  @DisplayName("TS 어댑터 사례 표의 응답·체크포인트로 수집하면")
  class AdapterCases {
    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.scos.connector.googledrive.internal.GoogleDriveAdapterFixtureTest#adapterCases")
    @DisplayName("TS와 같은 오류로 끝난다")
    void failsLikeTs(String label, JsonNode c) {
      // given
      drive.givenCase(c);

      // when
      Collection java = drive.collect(checkpointOf(c));

      // then
      assertThat(java.error())
          .isEqualTo(
              c.get("error").isNull()
                  ? Optional.empty()
                  : Optional.of(ErrorView.expected(c.get("error"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.scos.connector.googledrive.internal.GoogleDriveAdapterFixtureTest#adapterCases")
    @DisplayName("TS와 같은 페이지를 낸다")
    void pagesLikeTs(String label, JsonNode c) {
      // given
      drive.givenCase(c);

      // when
      Collection java = drive.collect(checkpointOf(c));

      // then
      assertThat(java.pageViews())
          .containsExactlyElementsOf(
              StreamSupport.stream(c.get("pages").spliterator(), false)
                  .map(PageView::expected)
                  .toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.scos.connector.googledrive.internal.GoogleDriveAdapterFixtureTest#adapterCases")
    @DisplayName("TS와 같은 원본을 낸다")
    void itemsLikeTs(String label, JsonNode c) {
      // given
      drive.givenCase(c);

      // when
      Collection java = drive.collect(checkpointOf(c));

      // then
      assertThat(java.upsertedItems())
          .containsExactlyElementsOf(
              StreamSupport.stream(c.get("items").spliterator(), false)
                  .map(ItemView::expected)
                  .toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.scos.connector.googledrive.internal.GoogleDriveAdapterFixtureTest#adapterCases")
    @DisplayName("TS와 같은 요청을 같은 순서로 보낸다")
    void requestsLikeTs(String label, JsonNode c) {
      // given
      drive.givenCase(c);

      // when
      drive.collect(checkpointOf(c));

      // then
      assertThat(drive.requests())
          .containsExactlyElementsOf(
              StreamSupport.stream(c.get("requests").spliterator(), false)
                  .map(RequestView::expected)
                  .toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.scos.connector.googledrive.internal.GoogleDriveAdapterFixtureTest#adapterCases")
    @DisplayName("재시도마다 TS와 같은 시간을 기다린다")
    void waitsLikeTs(String label, JsonNode c) {
      // given
      drive.givenCase(c);

      // when
      drive.collect(checkpointOf(c));

      // then
      assertThat(drive.waits()).containsExactlyElementsOf(expectedWaits(c));
    }
  }

  @Nested
  @DisplayName("coverage를 물으면")
  class Coverage {
    @ParameterizedTest(name = "{0}")
    @EnumSource(CollectionMode.class)
    @DisplayName("목록은 삭제를 증명하지 못하므로 어느 모드든 incremental이다")
    void coverageIsIncremental(CollectionMode mode) {
      // when
      CollectionCoverage coverage = drive.coverage(mode);

      // then
      assertThat(coverage).isEqualTo(CollectionCoverage.INCREMENTAL);
    }
  }

  @Nested
  @DisplayName("시작 전에 취소된 토큰으로 수집하면")
  class CancelledBeforeStart {
    @BeforeEach
    void givenCancelledBeforeStart() {
      drive.cancelBeforeStart();
    }

    @Test
    @DisplayName("요청 없이 끝난다")
    void cancelledBeforeStartSendsNothing() {
      // when
      drive.collect();

      // then
      assertThat(drive.requests()).isEmpty();
    }

    @Test
    @DisplayName("cancelled로 끝난다")
    void cancelledBeforeStartEndsCancelled() {
      // when
      Collection java = drive.collect();

      // then
      assertThat(java.failureKind()).contains("cancelled");
    }
  }

  /** 남은 작업이 공급자를 부르지 않는 삭제라 실행기의 취소 검사를 거치지 않는다 — 작업 루프의 검사만 멈출 수 있다. */
  @Nested
  @DisplayName("삭제 작업 둘이 남은 체크포인트에서 첫 페이지 뒤 취소되면")
  class CancelledMidRun {
    @BeforeEach
    void givenCancelAfterFirstPage() {
      drive.cancelAfterFirstPage();
    }

    @Test
    @DisplayName("다음 작업을 하지 않는다")
    void cancelledMidRunStopsBeforeNextTask() {
      // when
      Collection java = drive.collectFrom(GoogleDriveScenario.resumeRemovals("gone-1", "gone-2"));

      // then
      assertThat(java.pageViews()).hasSize(1);
    }

    @Test
    @DisplayName("cancelled로 끝난다")
    void cancelledMidRunEndsCancelled() {
      // when
      Collection java = drive.collectFrom(GoogleDriveScenario.resumeRemovals("gone-1", "gone-2"));

      // then
      assertThat(java.failureKind()).contains("cancelled");
    }
  }
}
