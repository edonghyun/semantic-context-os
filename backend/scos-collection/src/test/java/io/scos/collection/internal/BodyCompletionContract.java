package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.CollectionState;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.ReconciliationContext;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.connector.SourceOrder;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("본문 완료는 안정된 열거만 완료 버전으로 저장한다")
public abstract class BodyCompletionContract {
  static final SourceInstanceId SOURCE = new SourceInstanceId("body-source");
  static final String FINGERPRINT = "roots-generation";
  static final String PAGE = "page";
  static final String DELETED_OWNER = "deleted-owner";
  static final String BLOCK = "block:one";
  static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  static final SourceOrder ORDER = new SourceOrder("notion.page.v1", "0001");
  static final RunId RUN = new RunId("body-run");
  protected BodyCompletionStore bodies;
  protected RawItemStore raw;
  BodyCompletion completion;

  protected abstract void prepareStores();

  @BeforeEach
  void prepare() {
    prepareStores();
    raw.apply(
        SOURCE,
        RawItems.item(PAGE).resourceType("notion.page").version("v1").order(ORDER).upsert());
    completion = new BodyCompletion(bodies, raw, SnapshotShrinkLimits.DEFAULTS);
  }

  static final String DELETED_VERSION = "trashed:v2";
  static final SourceOrder DELETED_ORDER = new SourceOrder(ORDER.scheme(), "0002");
  static final SourceOrder RESTORED_ORDER = new SourceOrder(ORDER.scheme(), "0003");
  static final String LIVE_OWNER = "live-owner";
  // CollectionState의 공개 묶음 조회 한도.
  static final int MAX_SUMMARY_BATCH = 100;

  @Test
  @DisplayName("명시적 삭제 관측은 단독 소유 블록을 정리한다")
  void explicitDeletionCleansOwnedBody() {
    previousBody(Set.of(BLOCK));
    raw.apply(SOURCE, pageDeletion());
    applyObservation(deletedObservation());
    // then
    assertThat(raw.get(SOURCE, BLOCK).orElseThrow()).isInstanceOf(RawRecord.Deleted.class);
  }

  @Test
  @DisplayName("명시적 삭제 관측은 다른 살아 있는 소유자의 블록을 보존한다")
  void explicitDeletionPreservesSharedBlock() {
    sharedBodyWithDeletedPage();
    applyObservation(deletedObservation());
    // then
    assertThat(raw.get(SOURCE, BLOCK).orElseThrow()).isInstanceOf(RawRecord.Active.class);
  }

  @Test
  @DisplayName("복원된 페이지에 도착한 과거 삭제 관측은 블록을 정리하지 않는다")
  void staleDeletionPreservesRestoredBody() {
    previousBody(Set.of(BLOCK));
    raw.apply(SOURCE, RawItems.item(PAGE).version("v3").order(RESTORED_ORDER).upsert());
    applyObservation(deletedObservation());
    // then
    assertThat(raw.get(SOURCE, BLOCK).orElseThrow()).isInstanceOf(RawRecord.Active.class);
  }

  @Test
  @DisplayName("명시적 삭제는 미완료 본문도 닫아 다음 전체 정리를 허용한다")
  void explicitDeletionClosesPendingBody() {
    unfinishedBodyOfDeletedPage();
    applyObservation(deletedObservation());
    // then
    assertThat(bodies.pending(SOURCE, FINGERPRINT, PAGE)).isEmpty();
  }

  @Test
  @DisplayName("블록 이동 뒤 옛 owner가 삭제되면 현재 owner의 블록 부재를 정리한다")
  void deletedOtherOwnerDoesNotPreventCleanup() {
    previousBodyWithDeletedOtherOwner();
    applyObservation(observation(Set.of(), BodyObservation.Outcome.COMPLETE));
    // then
    assertThat(raw.get(SOURCE, BLOCK).orElseThrow()).isInstanceOf(RawRecord.Deleted.class);
  }

  @Test
  @DisplayName("안정된 본문 열거가 완료 버전을 만든다")
  void completesStableBody() {
    // given
    var observation =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of(BLOCK), BodyObservation.Outcome.COMPLETE);
    // when
    applyObservation(observation);
    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().version()).isEqualTo("v1");
  }

  @Test
  @DisplayName("미완료 열거는 완료 버전을 만들지 않는다")
  void unfinishedDoesNotComplete() {
    // given
    var observation =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of(BLOCK), BodyObservation.Outcome.ENUMERATING);
    // when
    applyObservation(observation);
    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE)).isEmpty();
  }

  @Test
  @DisplayName("완료된 본문에서 사라진 소유 블록을 정리한다")
  void removesAbsentOwnedBlock() {
    // given
    raw.apply(SOURCE, RawItems.item(BLOCK).resourceType("notion.block").version("v1").upsert());
    raw.apply(SOURCE, RawItems.item("block:kept").resourceType("notion.block").upsert());
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(
            FINGERPRINT, PAGE, "v0", ORDER, NOW.minusSeconds(1), Set.of(BLOCK, "block:kept")));
    var observation =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of("block:kept"), BodyObservation.Outcome.COMPLETE);
    // when
    applyObservation(observation);
    // then
    assertThat(raw.get(SOURCE, BLOCK).orElseThrow()).isInstanceOf(RawRecord.Deleted.class);
  }

  @Test
  @DisplayName("같은 run의 본문 페이지 관측을 완료 시 합친다")
  void combinesEnumerationPages() {
    // given
    applyObservation(
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of(BLOCK), BodyObservation.Outcome.ENUMERATING));
    var finished =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of("block:tail"), BodyObservation.Outcome.COMPLETE);
    // when
    applyObservation(finished);
    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().blocks())
        .containsExactlyInAnyOrder(BLOCK, "block:tail");
  }

  @Test
  @DisplayName("저장 완료보다 과거인 완료는 버전을 되돌리지 않는다")
  void rejectsOlderCompletion() {
    // given
    var newer = new SourceOrder(ORDER.scheme(), "0002");
    bodies.save(
        SOURCE, new BodyCompletionStore.Body(FINGERPRINT, PAGE, "v2", newer, NOW, Set.of(BLOCK)));
    var stale =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of("block:tail"), BodyObservation.Outcome.COMPLETE);
    // when
    applyObservation(stale);
    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().version()).isEqualTo("v2");
  }

  @Test
  @DisplayName("다른 페이지의 소속이 남은 블록은 정리하지 않는다")
  void preservesAnotherOwnersBlock() {
    // given
    raw.apply(SOURCE, RawItems.item(BLOCK).resourceType("notion.block").version("v1").upsert());
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(
            FINGERPRINT, PAGE, "v0", ORDER, NOW, Set.of(BLOCK, "block:kept")));
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(FINGERPRINT, "child", "v1", ORDER, NOW, Set.of(BLOCK)));
    var refreshed =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of("block:kept"), BodyObservation.Outcome.COMPLETE);
    // when
    applyObservation(refreshed);
    // then
    assertThat(raw.get(SOURCE, BLOCK).orElseThrow()).isInstanceOf(RawRecord.Active.class);
  }

  @Test
  @DisplayName("범위 축소 보호가 걸리면 완료 버전을 올리지 않는다")
  void shrinkGuardDefersCompletion() {
    // given
    largePreviousBody();
    var shrunken =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of("block:0"), BodyObservation.Outcome.COMPLETE);
    // when
    applyObservation(shrunken);
    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().version()).isEqualTo("v0");
  }

  @Test
  @DisplayName("축소 보호된 본문은 같은 버전이어도 미완료로 남긴다")
  void guardedBodyRemainsPending() {
    // given
    largePreviousBody();
    var shrunken =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of("block:0"), BodyObservation.Outcome.COMPLETE);
    // when
    applyObservation(shrunken);
    // then
    assertThat(bodies.pending(SOURCE, FINGERPRINT, PAGE)).isPresent();
  }

  @Test
  @DisplayName("raw가 더 최신이면 과거 본문을 완료하지 않는다")
  void requiresCurrentRawVersion() {
    // given
    raw.apply(
        SOURCE,
        RawItems.item(PAGE)
            .resourceType("notion.page")
            .version("v2")
            .order(new SourceOrder(ORDER.scheme(), "0002"))
            .upsert());
    var stale =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", ORDER, Set.of(BLOCK), BodyObservation.Outcome.COMPLETE);
    // when
    applyObservation(stale);
    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE)).isEmpty();
  }

  @Test
  @DisplayName("완료 요약은 같은 버전의 재수집을 생략할 수 있다")
  void completedSummarySkipsBody() {
    // given
    bodies.save(
        SOURCE, new BodyCompletionStore.Body(FINGERPRINT, PAGE, "v1", ORDER, NOW, Set.of(BLOCK)));
    // when
    var summaries =
        completion.state(SOURCE).bodies(FINGERPRINT, List.of(PAGE), CancellationToken.none());
    // then
    assertThat(summaries.get(PAGE).isCompleted("v1")).isTrue();
  }

  @Test
  @DisplayName("미완료 본문은 이전 완료와 같은 버전이어도 생략하지 않는다")
  void pendingSummaryRequiresRefresh() {
    // given
    bodies.save(
        SOURCE, new BodyCompletionStore.Body(FINGERPRINT, PAGE, "v1", ORDER, NOW, Set.of(BLOCK)));
    bodies.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(FINGERPRINT, PAGE, "v1", ORDER, RUN, Set.of(BLOCK)));
    // when
    var summaries =
        completion.state(SOURCE).bodies(FINGERPRINT, List.of(PAGE), CancellationToken.none());
    // then
    assertThat(summaries.get(PAGE).isCompleted("v1")).isFalse();
  }

  @Test
  @DisplayName("취소된 요약 조회는 저장소를 읽지 않고 중단한다")
  void cancelledSummaryStops() {
    // given
    var cancelled = CancellationToken.none();
    cancelled.cancel();
    // when·then
    assertThatThrownBy(() -> completion.state(SOURCE).bodies(FINGERPRINT, List.of(PAGE), cancelled))
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error -> assertThat(error.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
  }

  @Test
  @DisplayName("요약 조회가 묶음 한도를 넘으면 거부한다")
  void rejectsOversizedSummaryBatch() {
    // given
    var oversized = Collections.nCopies(CollectionState.MAX_BATCH + 1, PAGE);
    // when·then
    assertThatThrownBy(
            () -> completion.state(SOURCE).bodies(FINGERPRINT, oversized, CancellationToken.none()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("완료 전 검증한 소속도 같은 세대 요약에 남긴다")
  void pendingMembershipIsVisible() {
    // given
    bodies.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(FINGERPRINT, PAGE, "v1", ORDER, RUN, Set.of(BLOCK)));
    // when
    var summaries =
        completion.state(SOURCE).bodies(FINGERPRINT, List.of(PAGE), CancellationToken.none());
    // then
    assertThat(summaries.get(PAGE)).isNotNull();
    assertThat(summaries.get(PAGE).verifiedMembership()).isTrue();
  }

  @Test
  @DisplayName("삭제된 페이지는 과거 완료 버전으로 생략하지 않는다")
  void deletedSummaryRequiresRefresh() {
    // given
    bodies.save(
        SOURCE, new BodyCompletionStore.Body(FINGERPRINT, PAGE, "v1", ORDER, NOW, Set.of(BLOCK)));
    raw.tombstoneSelected(SOURCE, Set.of(PAGE), NOW);
    // when
    var summaries =
        completion.state(SOURCE).bodies(FINGERPRINT, List.of(PAGE), CancellationToken.none());
    // then
    assertThat(summaries.get(PAGE).isCompleted("v1")).isFalse();
  }

  @Test
  @DisplayName("본문 완료의 순서 scheme이 현재 페이지와 다르면 거부한다")
  void rejectsDifferentOrderScheme() {
    // given
    var wrongOrder = new SourceOrder("other.page.v1", ORDER.position());
    var invalid =
        new BodyObservation(
            FINGERPRINT, PAGE, "v1", wrongOrder, Set.of(BLOCK), BodyObservation.Outcome.COMPLETE);
    // when·then
    assertThatThrownBy(() -> applyObservation(invalid))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("부모 본문을 정리해도 자식 페이지 소유 블록은 남긴다")
  void preservesChildBody() {
    // given
    previousBody(Set.of(BLOCK, "block:kept"));
    raw.apply(SOURCE, RawItems.item("block:child").resourceType("notion.block").upsert());
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(
            FINGERPRINT, "child", "v1", ORDER, NOW, Set.of("block:child")));
    // when
    applyObservation(observation(Set.of("block:kept"), BodyObservation.Outcome.COMPLETE));
    // then
    assertThat(raw.get(SOURCE, "block:child").orElseThrow()).isInstanceOf(RawRecord.Active.class);
  }

  @Test
  @DisplayName("미완료 열거는 기존 소유 블록을 지우지 않는다")
  void incompletePreservesBlocks() {
    // given
    previousBody(Set.of(BLOCK, "block:kept"));
    // when
    applyObservation(observation(Set.of(), BodyObservation.Outcome.ENUMERATING));
    // then
    assertThat(raw.get(SOURCE, BLOCK).orElseThrow()).isInstanceOf(RawRecord.Active.class);
  }

  @Test
  @DisplayName("검증된 빈 본문의 대량 부재는 축소 보호로 보류한다")
  void emptyBodyIsGuarded() {
    // given
    largePreviousBody();
    // when
    applyObservation(observation(Set.of(), BodyObservation.Outcome.COMPLETE));
    // then
    assertThat(bodies.pending(SOURCE, FINGERPRINT, PAGE)).isPresent();
    assertThat(raw.get(SOURCE, "block:0").orElseThrow()).isInstanceOf(RawRecord.Active.class);
  }

  @Test
  @DisplayName("새 run의 완료에 이전 run의 미완료 관측을 합치지 않는다")
  void restartDiscardsOldObservations() {
    // given
    bodies.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(
            FINGERPRINT, PAGE, "v1", ORDER, new RunId("old-run"), Set.of(BLOCK)));
    // when
    applyObservation(observation(Set.of("block:new"), BodyObservation.Outcome.COMPLETE));
    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().blocks())
        .containsExactly("block:new");
  }

  @Test
  @DisplayName("다음 안정된 열거는 미완료 버전이 남긴 블록도 정리한다")
  void removesBlocksFromIncompleteVersion() {
    // given
    raw.apply(SOURCE, RawItems.item(BLOCK).resourceType("notion.block").upsert());
    raw.apply(SOURCE, RawItems.item("block:kept").resourceType("notion.block").upsert());
    bodies.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(
            FINGERPRINT, PAGE, "v0", ORDER, RUN, Set.of(BLOCK, "block:kept")));
    // when
    applyObservation(observation(Set.of("block:kept"), BodyObservation.Outcome.COMPLETE));
    // then
    assertThat(raw.get(SOURCE, BLOCK).orElseThrow()).isInstanceOf(RawRecord.Deleted.class);
  }

  @Test
  @DisplayName("새 페이지 조회 사실은 같은 run·버전의 옛 본문 관측을 다시 연다")
  void freshReadResetsSameVersionObservations() {
    // given
    bodies.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(FINGERPRINT, PAGE, "v1", ORDER, RUN, Set.of(BLOCK)));
    var page =
        new CollectionPage(
            List.of(RawItems.item(PAGE).version("v1").order(ORDER).upsert()),
            List.of(),
            new Checkpoint(4, "{}"),
            List.of(observation(Set.of(), BodyObservation.Outcome.ENUMERATING)));
    // when
    completion.apply(SOURCE, RUN, page, NOW);
    // then
    assertThat(bodies.pending(SOURCE, FINGERPRINT, PAGE).orElseThrow().observed()).isEmpty();
    assertThat(bodies.members(SOURCE, FINGERPRINT, PAGE)).contains(BLOCK);
  }

  @Test
  @DisplayName("검증된 소규모 빈 본문은 이전 블록을 정리한다")
  void stableSmallEmptyBodyRemovesPreviousBlock() {
    // given
    previousBody(Set.of(BLOCK));
    // when
    applyObservation(observation(Set.of(), BodyObservation.Outcome.COMPLETE));
    // then
    assertThat(raw.get(SOURCE, BLOCK).orElseThrow()).isInstanceOf(RawRecord.Deleted.class);
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().version()).isEqualTo("v1");
  }

  @Test
  @DisplayName("본문 정리는 실제 삭제한 소유 블록 ID를 반환한다")
  void cleanupReturnsDeletedBlockIds() {
    previousBody(Set.of(BLOCK));
    var removed = applyObservation(observation(Set.of(), BodyObservation.Outcome.COMPLETE));

    // then
    assertThat(removed).containsExactly(BLOCK);
  }

  @Test
  @DisplayName("명시적 페이지 삭제도 실제 삭제한 블록 ID를 반환한다")
  void deletionReturnsDeletedBlockIds() {
    previousBody(Set.of(BLOCK));
    raw.apply(SOURCE, pageDeletion());
    var removed = applyObservation(deletedObservation());

    // then
    assertThat(removed).containsExactly(BLOCK);
  }

  @Test
  @DisplayName("축소 보호 최소 규모에서 절반을 넘는 삭제는 완료를 보류한다")
  void minimumGuardSizeStillProtectsMajorityLoss() {
    largePreviousBody();
    var retained = Set.of("block:0", "block:1", "block:2", "block:3");
    applyObservation(observation(retained, BodyObservation.Outcome.COMPLETE));

    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().version()).isEqualTo("v0");
  }

  @Test
  @DisplayName("축소 보호 상한과 같은 절반 삭제는 완료한다")
  void exactDeletionRatioIsAllowed() {
    largePreviousBody();
    var retained = Set.of("block:0", "block:1", "block:2", "block:3", "block:4");
    applyObservation(observation(retained, BodyObservation.Outcome.COMPLETE));

    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().blocks())
        .containsExactlyInAnyOrderElementsOf(retained);
  }

  @Test
  @DisplayName("묶음 한도와 같은 개수의 본문 요약은 조회할 수 있다")
  void exactSummaryBatchLimitIsAllowed() {
    previousBody(Set.of(BLOCK));
    var ids = Collections.nCopies(MAX_SUMMARY_BATCH, PAGE);
    var summary = completion.state(SOURCE).bodies(FINGERPRINT, ids, CancellationToken.none());

    // then
    assertThat(summary.get(PAGE).completion()).isPresent();
  }

  @Test
  @DisplayName("완료 버전과 다른 확인 관측은 마지막 읽기 증거를 갱신하지 않는다")
  void confirmationCannotReadDifferentCompletedVersion() {
    previousBody(Set.of(BLOCK));
    applyObservation(confirmation());

    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().lastReading()).isEmpty();
  }

  @Test
  @DisplayName("같은 버전이어도 미완료 열거가 남으면 확인 관측으로 읽기를 확정하지 않는다")
  void confirmationCannotReadPendingBody() {
    bodies.save(
        SOURCE, new BodyCompletionStore.Body(FINGERPRINT, PAGE, "v1", ORDER, NOW, Set.of(BLOCK)));
    bodies.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(FINGERPRINT, PAGE, "v1", ORDER, RUN, Set.of(BLOCK)));
    applyObservation(confirmation());

    // then
    assertThat(bodies.find(SOURCE, FINGERPRINT, PAGE).orElseThrow().lastReading()).isEmpty();
  }

  @Test
  @DisplayName("삭제된 소유자의 블록은 살아 있는 다른 소유자가 있으면 정리 결과에서 빠진다")
  void deletedOwnerCannotClaimSharedBlockWasRemoved() {
    sharedBodyWithDeletedPage();
    var removed = applyObservation(deletedObservation());

    // then
    assertThat(removed).isEmpty();
  }

  @Test
  @DisplayName("이미 삭제된 소유 블록은 현재 축소 보호 규모에서 빠진다")
  void deletedBlocksDoNotInflateShrinkGuard() {
    largePreviousBody();
    var alreadyDeleted =
        bodies.members(SOURCE, FINGERPRINT, PAGE).stream()
            .filter(id -> !id.equals("block:0"))
            .collect(Collectors.toSet());
    raw.tombstoneSelected(SOURCE, alreadyDeleted, NOW);
    var removed = applyObservation(observation(Set.of(), BodyObservation.Outcome.COMPLETE));
    // then
    assertThat(removed).containsExactly("block:0");
  }

  private BodyObservation confirmation() {
    return new BodyObservation(
        FINGERPRINT,
        PAGE,
        "v1",
        ORDER,
        Set.of(),
        BodyObservation.Outcome.CONFIRMED,
        Optional.of(
            ReconciliationContexts.withDefaultGrace(
                ReconciliationContext.Path.INCREMENTAL,
                Optional.empty(),
                Optional.empty(),
                false)));
  }

  private RawMutation.Delete pageDeletion() {
    return new RawMutation.Delete(
        PAGE, Optional.of(DELETED_VERSION), Optional.of(DELETED_ORDER), Optional.of(NOW), NOW);
  }

  private BodyObservation deletedObservation() {
    return new BodyObservation(
        FINGERPRINT,
        PAGE,
        DELETED_VERSION,
        DELETED_ORDER,
        Set.of(),
        BodyObservation.Outcome.DELETED);
  }

  private void sharedBodyWithDeletedPage() {
    previousBody(Set.of(BLOCK));
    raw.apply(SOURCE, RawItems.item(LIVE_OWNER).resourceType("notion.page").upsert());
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(FINGERPRINT, LIVE_OWNER, "v1", ORDER, NOW, Set.of(BLOCK)));
    raw.apply(SOURCE, pageDeletion());
  }

  private void unfinishedBodyOfDeletedPage() {
    previousBody(Set.of(BLOCK));
    bodies.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(FINGERPRINT, PAGE, "v1", ORDER, RUN, Set.of(BLOCK)));
    raw.apply(SOURCE, pageDeletion());
  }

  private List<String> applyObservation(BodyObservation observation) {
    return completion.apply(
        SOURCE,
        RUN,
        new CollectionPage(List.of(), List.of(), new Checkpoint(4, "{}"), List.of(observation)),
        NOW);
  }

  private BodyObservation observation(Set<String> blocks, BodyObservation.Outcome outcome) {
    return new BodyObservation(FINGERPRINT, PAGE, "v1", ORDER, blocks, outcome);
  }

  private void previousBodyWithDeletedOtherOwner() {
    previousBody(Set.of(BLOCK));
    raw.apply(SOURCE, RawItems.item(DELETED_OWNER).resourceType("notion.page").upsert());
    bodies.save(
        SOURCE,
        new BodyCompletionStore.Body(FINGERPRINT, DELETED_OWNER, "v0", ORDER, NOW, Set.of(BLOCK)));
    raw.tombstoneSelected(SOURCE, Set.of(DELETED_OWNER), NOW);
  }

  private void previousBody(Set<String> blocks) {
    blocks.forEach(
        id -> raw.apply(SOURCE, RawItems.item(id).resourceType("notion.block").upsert()));
    bodies.save(SOURCE, new BodyCompletionStore.Body(FINGERPRINT, PAGE, "v0", ORDER, NOW, blocks));
  }

  private void largePreviousBody() {
    final int referenceBodySize = 10; // 축소 보호 기본 계약의 비율 적용 최소 규모.
    Set<String> blocks =
        IntStream.range(0, referenceBodySize)
            .mapToObj(i -> "block:" + i)
            .collect(Collectors.toSet());
    blocks.forEach(
        id ->
            raw.apply(
                SOURCE, RawItems.item(id).resourceType("notion.block").version("v1").upsert()));
    bodies.save(SOURCE, new BodyCompletionStore.Body(FINGERPRINT, PAGE, "v0", ORDER, NOW, blocks));
  }
}
