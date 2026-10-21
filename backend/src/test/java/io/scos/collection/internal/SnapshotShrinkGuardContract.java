package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.SnapshotGuardStore.Acceptance;
import io.scos.collection.internal.SnapshotGuardStore.Guarded;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 스냅샷 축소 보호(ADR-0028)의 완료 계약. succeeded + complete_snapshot run이 정리 직전 active의 너무 많은 부분을 관측하지 못했으면
 * 정리하지 않는다 — 공급자 장애·권한 변경으로 빈 목록이나 크게 줄어든 목록이 200으로 와도 그 소스의 검색 데이터가 한꺼번에 사라지지 않게(사용자 결정
 * 2026-10-16, TS와 다른 의도한 차이). 기준은 기본값(최소 10건, 상한 50%)이다. 같은 사례를 메모리 포트와 PostgreSQL 포트가 지킨다. 완료
 * 트랜잭션의 원자성은 PostgreSQL만의 성질이라 {@code PostgresSnapshotShrinkGuardAtomicityTest}에 있다.
 */
@DisplayName("스냅샷 축소 보호 — 크게 줄어든 완전 스냅샷은 정리하지 않는다(ADR-0028)")
public abstract class SnapshotShrinkGuardContract {
  static final SourceInstanceId SOURCE = new SourceInstanceId("tiro-main");
  static final Instant REGISTERED = Instant.parse("2026-10-16T00:00:00Z");
  static final Instant ACCEPTED = REGISTERED.plusSeconds(60);
  static final Instant FINISHED = REGISTERED.plusSeconds(120);
  static final Duration TTL = Duration.ofSeconds(30);

  /** 허용 유효 기간 기본값(7일) — 그 직전과 그 순간. */
  static final Duration ACCEPT_TTL = Duration.ofDays(7);

  static final Duration JUST_BEFORE_EXPIRY = ACCEPT_TTL.minusMillis(1);

  /** 완료가 함께 쓰는 포트 묶음(같은 구현 계열, 같은 트랜잭션). */
  public record Ports(
      TransactionOperations tx,
      WorkClaimPort claims,
      RunStore runs,
      RawItemStore rawItems,
      OutboxPort outbox,
      SourceChangeLog changes,
      SourceInstanceStore sources,
      SnapshotGuardStore guards) {}

  /** 비어 있는 새 저장소 위의 포트 묶음. */
  protected abstract Ports newPorts();

  private Ports ports;
  private RunCompletion completion;
  private final MutableClock clock = new MutableClock(FINISHED);
  private final List<String> activeIds = new ArrayList<>();
  private int runs;
  private WorkClaim claim;

  @BeforeEach
  void assemble() {
    ports = newPorts();
    completion =
        new RunCompletion(
            ports.tx(),
            ports.claims(),
            ports.runs(),
            ports.rawItems(),
            ports.outbox(),
            ports.changes(),
            new SnapshotShrinkGuard(
                ports.sources(),
                ports.guards(),
                SnapshotShrinkLimits.DEFAULTS,
                TestMetrics.collection()));
    ports.sources().save(SourceInstances.noop(SOURCE, REGISTERED));
  }

  // ---- given ----

  /** 이 소스에 active 원본이 count건 있다. */
  void givenActive(int count) {
    for (int i = activeIds.size(); i < count; i++) {
      String id = String.format("note-%03d", i);
      ports
          .rawItems()
          .apply(SOURCE, new RawMutation.Upsert(RawItem.minimal(id, "v1", "{}", REGISTERED)));
      activeIds.add(id);
    }
    if (ports.rawItems().count(SOURCE) != count)
      throw new IllegalStateException(
          "given 준비 실패: active " + count + "건을 기대했지만 " + ports.rawItems().count(SOURCE) + "건");
  }

  /** 소스별 기준을 가진 등록으로 바꾼다. */
  void givenSourceLimits(SnapshotShrinkOverrides overrides) {
    SourceInstance noop = SourceInstances.noop(SOURCE, REGISTERED);
    ports
        .sources()
        .save(
            new SourceInstance(
                noop.id(),
                noop.kind(),
                noop.displayName(),
                noop.config(),
                noop.credentialRef(),
                true,
                noop.schedule(),
                overrides,
                REGISTERED,
                REGISTERED));
  }

  /** 운영자가 축소를 허용했다. */
  void givenShrinkAccepted() {
    ports.guards().acceptShrink(SOURCE, ACCEPTED);
  }

  /** 새 run이 점유를 잡고 시작해 active 원본 중 앞의 observed건만 관측했다. 아직 완료하지 않았다. */
  RunId givenRunObserving(int observed) {
    RunId run = new RunId("run-" + ++runs);
    claim =
        ports
            .claims()
            .tryClaim(SOURCE, new OwnerId("worker"), TTL, clock.instant())
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: 점유를 잡지 못했다"));
    ports.runs().start(run, SOURCE, clock.instant());
    completion.recordSnapshotBaseline(SOURCE, run); // 런타임이 complete_snapshot run을 시작할 때처럼
    ports.runs().observe(run, activeIds.subList(0, observed));
    return run;
  }

  /** 이 run이 이전에 없던 원본 count건을 새로 넣었다(관측됨). */
  void givenRunAddedNewItems(RunId run, int count) {
    List<String> added = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String id = String.format("new-%03d", i);
      ports
          .rawItems()
          .apply(SOURCE, new RawMutation.Upsert(RawItem.minimal(id, "v1", "{}", REGISTERED)));
      added.add(id);
    }
    ports.runs().observe(run, added);
  }

  /** 지금 운영자가 축소를 허용했다. 허용 시각을 돌려준다. */
  Instant givenShrinkAcceptedNow() {
    ports.guards().acceptShrink(SOURCE, clock.instant());
    return clock.instant();
  }

  /** 시간이 이만큼 지났다. */
  void givenTimePassed(Duration elapsed) {
    clock.advance(elapsed);
  }

  /** 아무것도 관측하지 못한 run이 count번 연달아 보호됐다. */
  void givenGuardedRuns(int count) {
    for (int i = 0; i < count; i++) completeSucceeded(givenRunObserving(0));
    if (ports.guards().lastGuarded(SOURCE).isEmpty())
      throw new IllegalStateException("given 준비 실패: 보호된 run이 없다");
  }

  /** 시작한 run이 완료 전에 멈췄다(kill) — 점유는 TTL이 지나 풀린다. */
  void givenRunStoppedBeforeCompletion() {
    clock.advance(TTL.plusSeconds(1));
  }

  // ---- when ----

  CollectionRun completeSucceeded(RunId run) {
    return complete(run, RunStatus.SUCCEEDED);
  }

  CollectionRun complete(RunId run, RunStatus status) {
    CollectionRun done =
        completion.complete(
            new RunCompletion.Input(
                claim,
                run,
                status,
                CollectionCoverage.COMPLETE_SNAPSHOT,
                clock.instant(),
                0,
                Optional.empty(),
                Optional.empty()));
    ports.claims().release(claim, clock.instant());
    return done;
  }

  // ---- then ----

  void assertActiveCount(int expected) {
    assertThat(ports.rawItems().count(SOURCE)).as("active 원본 수").isEqualTo(expected);
  }

  void assertTombstoneCount(int expected) {
    assertThat(ports.rawItems().tombstoneCount(SOURCE)).as("tombstone 수").isEqualTo(expected);
  }

  void assertFinishedGuarded(CollectionRun run) {
    assertThat(run)
        .as("정리를 건너뛰고 succeeded로 끝난 run(errorKind snapshot_shrink_guarded, 오류 0, 지운 것 0)")
        .extracting(
            CollectionRun::status,
            CollectionRun::errorKind,
            CollectionRun::errorCount,
            CollectionRun::deleted)
        .containsExactly(RunStatus.SUCCEEDED, Optional.of("snapshot_shrink_guarded"), 0, 0);
  }

  void assertFinishedReconciled(CollectionRun run, int deleted) {
    assertThat(run)
        .as("정리하고 오류 없이 succeeded로 끝난 run")
        .extracting(
            CollectionRun::status,
            CollectionRun::errorKind,
            CollectionRun::errorCount,
            CollectionRun::deleted)
        .containsExactly(RunStatus.SUCCEEDED, Optional.empty(), 0, deleted);
  }

  // ---- 계약 ----

  @Nested
  @DisplayName("active 20건인 소스에서 run이 하나도 관측하지 못하면")
  class NothingObserved {
    RunId run;
    CollectionRun finished;

    @BeforeEach
    void givenEmptyListing() {
      givenActive(20);
      run = givenRunObserving(0);

      finished = completeSucceeded(run);
    }

    @Test
    @DisplayName("원본을 하나도 지우지 않는다")
    void keepsEveryItem() {
      // then
      assertActiveCount(20);
    }

    @Test
    @DisplayName("run은 보호 사유가 붙은 succeeded로 끝난다")
    void finishesSucceededWithGuardKind() {
      // then
      assertFinishedGuarded(finished);
    }

    @Test
    @DisplayName("run 오류 요약에 지우지 않은 수와 기준을 남긴다")
    void summarizesSkippedReconcile() {
      // then
      assertThat(finished.errorSummary())
          .contains(
              "complete snapshot reconcile skipped: 20 of 20 active items unobserved (limit 50% at 10+ items)");
    }

    @Test
    @DisplayName("보호 판정을 소스 상태로 남긴다")
    void recordsGuardedState() {
      // then
      assertThat(ports.guards().lastGuarded(SOURCE))
          .contains(new Guarded(run, FINISHED, 20, 20, 1));
    }

    @Test
    @DisplayName("스냅샷 완료를 기록하지 않는다")
    void recordsNoSnapshotCompletion() {
      // then
      assertThat(ports.runs().lastSnapshotCompletion(SOURCE)).isEmpty();
    }

    @Test
    @DisplayName("변경 로그에 아무것도 남기지 않는다")
    void appendsNoChange() {
      // then
      assertThat(ports.changes().read(Optional.of(SOURCE), 0, 10).changes()).isEmpty();
    }

    @Test
    @DisplayName("투영을 요청하지 않는다")
    void requestsNoProjection() {
      // then
      assertThat(ports.outbox().pending()).isEmpty();
    }
  }

  @Nested
  @DisplayName("최소 규모보다 작은 3건 소스에서 하나도 관측하지 못하면")
  class NothingObservedInSmallSource {
    @BeforeEach
    void givenSmallSource() {
      givenActive(3);
    }

    @Test
    @DisplayName("규모와 무관하게 원본을 지우지 않는다")
    void keepsEveryItem() {
      // given
      RunId run = givenRunObserving(0);

      // when
      completeSucceeded(run);

      // then
      assertActiveCount(3);
    }
  }

  @Nested
  @DisplayName("active 100건인 소스에서")
  class HundredItems {
    @BeforeEach
    void givenHundredItems() {
      givenActive(100);
    }

    @Test
    @DisplayName("51건을 관측하지 못하면 원본을 지우지 않는다")
    void keepsItemsWhenOverLimit() {
      // given
      RunId run = givenRunObserving(49);

      // when
      completeSucceeded(run);

      // then
      assertActiveCount(100);
    }

    @Test
    @DisplayName("51건을 관측하지 못한 run에는 보호 사유가 붙는다")
    void overLimitRunCarriesGuardKind() {
      // given
      RunId run = givenRunObserving(49);

      // when
      CollectionRun finished = completeSucceeded(run);

      // then
      assertFinishedGuarded(finished);
    }

    @Test
    @DisplayName("49건을 관측하지 못하면 그 49건을 지운다")
    void reconcilesUnderLimit() {
      // given
      RunId run = givenRunObserving(51);

      // when
      completeSucceeded(run);

      // then
      assertTombstoneCount(49);
    }

    @Test
    @DisplayName("49건을 지운 run은 보호 사유 없이 끝난다")
    void underLimitRunHasNoGuardKind() {
      // given
      RunId run = givenRunObserving(51);

      // when
      CollectionRun finished = completeSucceeded(run);

      // then
      assertFinishedReconciled(finished, 49);
    }

    @Test
    @DisplayName("정확히 50건을 관측하지 못하면 상한 안이라 지운다")
    void reconcilesAtLimit() {
      // given
      RunId run = givenRunObserving(50);

      // when
      completeSucceeded(run);

      // then
      assertTombstoneCount(50);
    }
  }

  @Nested
  @DisplayName("최소 규모보다 작은 9건 소스에서 8건을 관측하지 못하면")
  class SmallSourceShrinks {
    @BeforeEach
    void givenNineItems() {
      givenActive(9);
    }

    @Test
    @DisplayName("비율과 무관하게 그 8건을 지운다")
    void reconcilesBelowMinimum() {
      // given
      RunId run = givenRunObserving(1);

      // when
      completeSucceeded(run);

      // then
      assertTombstoneCount(8);
    }
  }

  @Nested
  @DisplayName("소스별 기준이 있는 100건 소스에서 51건을 관측하지 못하면")
  class PerSourceLimits {
    @BeforeEach
    void givenHundredItems() {
      givenActive(100);
    }

    @Test
    @DisplayName("소스 상한이 60%면 그 51건을 지운다")
    void sourceRatioOverridesDefault() {
      // given
      givenSourceLimits(new SnapshotShrinkOverrides(Optional.empty(), Optional.of(60)));
      RunId run = givenRunObserving(49);

      // when
      completeSucceeded(run);

      // then
      assertTombstoneCount(51);
    }

    @Test
    @DisplayName("소스 최소 규모가 200건이면 그 51건을 지운다")
    void sourceMinimumOverridesDefault() {
      // given
      givenSourceLimits(new SnapshotShrinkOverrides(Optional.of(200), Optional.empty()));
      RunId run = givenRunObserving(49);

      // when
      completeSucceeded(run);

      // then
      assertTombstoneCount(51);
    }
  }

  @Nested
  @DisplayName("축소를 허용한 100건 소스에서 51건을 관측하지 못하면")
  class AcceptedShrink {
    RunId run;
    CollectionRun finished;

    @BeforeEach
    void givenAcceptedShrink() {
      givenActive(100);
      givenShrinkAccepted();
      run = givenRunObserving(49);

      finished = completeSucceeded(run);
    }

    @Test
    @DisplayName("관측하지 못한 51건을 지운다")
    void reconcilesBeyondLimit() {
      // then
      assertTombstoneCount(51);
    }

    @Test
    @DisplayName("run은 보호 사유 없이 succeeded로 끝난다")
    void finishesWithoutGuardKind() {
      // then
      assertFinishedReconciled(finished, 51);
    }

    @Test
    @DisplayName("허용 표시는 이 run이 쓴 것으로 소모된다")
    void consumesAcceptance() {
      // then
      assertThat(ports.guards().lastAcceptance(SOURCE))
          .contains(
              new Acceptance(ACCEPTED, Optional.of(FINISHED), Optional.of(run), Optional.empty()));
    }
  }

  @Nested
  @DisplayName("보호된 뒤 축소를 허용하고 다음 run이 끝나면")
  class AcceptedAfterGuardedRun {
    @BeforeEach
    void givenGuardedThenAccepted() {
      givenActive(100);
      completeSucceeded(givenRunObserving(0));
      if (ports.guards().lastGuarded(SOURCE).isEmpty())
        throw new IllegalStateException("given 준비 실패: 첫 run이 보호되지 않았다");
      givenShrinkAccepted();

      completeSucceeded(givenRunObserving(0));
    }

    @Test
    @DisplayName("허용으로 정리했으므로 보호 상태가 풀린다")
    void clearsGuardedState() {
      // then
      assertThat(ports.guards().lastGuarded(SOURCE)).isEmpty();
    }

    @Test
    @DisplayName("관측하지 못한 100건을 모두 지운다")
    void reconcilesEverything() {
      // then
      assertActiveCount(0);
    }
  }

  @Nested
  @DisplayName("아무것도 관측하지 못한 보호가 정리 없이 세 번 이어지면")
  class GuardedThreeTimes {
    @BeforeEach
    void givenThreeGuardedRuns() {
      givenActive(100);

      givenGuardedRuns(3);
    }

    @Test
    @DisplayName("보호 기록은 연속 3회다")
    void countsConsecutiveGuards() {
      // then
      assertThat(ports.guards().lastGuarded(SOURCE))
          .hasValueSatisfying(g -> assertThat(g.consecutiveCount()).isEqualTo(3));
    }
  }

  @Nested
  @DisplayName("보호가 두 번 이어진 뒤 정상 정리되고 다시 보호되면")
  class GuardedAgainAfterReconcile {
    @BeforeEach
    void givenGuardedReconciledGuarded() {
      givenActive(100);
      givenGuardedRuns(2);
      completeSucceeded(givenRunObserving(100));
      if (ports.guards().lastGuarded(SOURCE).isPresent())
        throw new IllegalStateException("given 준비 실패: 정상 정리 뒤에도 보호 기록이 남았다");

      completeSucceeded(givenRunObserving(0));
    }

    @Test
    @DisplayName("연속 횟수는 1부터 다시 센다")
    void restartsCount() {
      // then
      assertThat(ports.guards().lastGuarded(SOURCE))
          .hasValueSatisfying(g -> assertThat(g.consecutiveCount()).isEqualTo(1));
    }
  }

  @Nested
  @DisplayName("허용한 지 7일이 되기 직전에 51건을 관측하지 못하면")
  class AcceptedJustBeforeExpiry {
    @BeforeEach
    void givenAcceptanceAboutToExpire() {
      givenActive(100);
      givenShrinkAcceptedNow();
      givenTimePassed(JUST_BEFORE_EXPIRY);

      completeSucceeded(givenRunObserving(49));
    }

    @Test
    @DisplayName("허용이 아직 유효해 51건을 지운다")
    void reconcilesWithAcceptance() {
      // then
      assertTombstoneCount(51);
    }
  }

  @Nested
  @DisplayName("허용한 지 정확히 7일에 51건을 관측하지 못하면")
  class AcceptedAtExpiry {
    Instant acceptedAt;
    CollectionRun finished;

    @BeforeEach
    void givenExpiredAcceptance() {
      givenActive(100);
      acceptedAt = givenShrinkAcceptedNow();
      givenTimePassed(ACCEPT_TTL);

      finished = completeSucceeded(givenRunObserving(49));
    }

    @Test
    @DisplayName("만료된 허용은 쓰지 않아 원본을 지우지 않는다")
    void keepsItems() {
      // then
      assertActiveCount(100);
    }

    @Test
    @DisplayName("run은 보호 사유가 붙은 succeeded로 끝난다")
    void runIsGuarded() {
      // then
      assertFinishedGuarded(finished);
    }

    @Test
    @DisplayName("허용 표시는 소모되지 않고 만료로 남는다")
    void marksAcceptanceExpired() {
      // then
      assertThat(ports.guards().lastAcceptance(SOURCE))
          .contains(
              new Acceptance(
                  acceptedAt, Optional.empty(), Optional.empty(), Optional.of(clock.instant())));
    }
  }

  @Nested
  @DisplayName("허용으로 정리한 뒤 다음 run이 하나도 관측하지 못하면")
  class NextRunAfterAcceptedReconcile {
    CollectionRun next;

    @BeforeEach
    void givenAcceptedReconcileThenEmptyListing() {
      givenActive(100);
      givenShrinkAccepted();
      completeSucceeded(givenRunObserving(49));
      if (ports.rawItems().count(SOURCE) != 49)
        throw new IllegalStateException("given 준비 실패: 허용으로 51건을 지우지 않았다");

      next = completeSucceeded(givenRunObserving(0));
    }

    @Test
    @DisplayName("허용은 한 번만이라 남은 49건을 지우지 않는다")
    void keepsRemainingItems() {
      // then
      assertActiveCount(49);
    }

    @Test
    @DisplayName("다음 run은 보호 사유가 붙은 succeeded로 끝난다")
    void nextRunIsGuarded() {
      // then
      assertFinishedGuarded(next);
    }
  }

  @Nested
  @DisplayName("기존 100건을 하나도 관측하지 못하고 새 100건을 넣으면")
  class ReplacedEverything {
    @BeforeEach
    void givenReplacedListing() {
      givenActive(100);
      RunId run = givenRunObserving(0);
      givenRunAddedNewItems(run, 100);

      completeSucceeded(run);
    }

    @Test
    @DisplayName("새 항목에 묻히지 않고 기존 100건을 지우지 않는다")
    void keepsExistingItems() {
      // then
      assertTombstoneCount(0);
    }

    @Test
    @DisplayName("보호 판정의 분모는 run 전부터 있던 100건이다")
    void judgesAgainstExistingItems() {
      // then
      assertThat(ports.guards().lastGuarded(SOURCE))
          .hasValueSatisfying(g -> assertThat(g.activeCount()).isEqualTo(100));
    }
  }

  @Nested
  @DisplayName("기존 100건 중 60건을 관측하지 못하고 새 100건을 넣으면")
  class ReplacedMostItems {
    @Test
    @DisplayName("기존 기준 60%라 지우지 않는다")
    void keepsExistingItems() {
      // given
      givenActive(100);
      RunId run = givenRunObserving(40);
      givenRunAddedNewItems(run, 100);

      // when
      completeSucceeded(run);

      // then
      assertTombstoneCount(0);
    }
  }

  @Nested
  @DisplayName("run 시작 기준 저장소에서")
  class BaselineStore {
    @Test
    @DisplayName("다른 run이 남긴 기준은 이 run의 분모로 읽히지 않는다")
    void otherRunsBaselineIsEmpty() {
      // given
      ports.guards().recordBaseline(SOURCE, new RunId("run-a"), 7);

      // when
      Optional<Integer> baseline = ports.guards().baseline(SOURCE, new RunId("run-b"));

      // then
      assertThat(baseline).isEmpty();
    }
  }

  @Nested
  @DisplayName("허용 표시 저장소에서")
  class AcceptanceStore {
    Instant acceptedAt;

    @BeforeEach
    void givenAcceptance() {
      acceptedAt = givenShrinkAcceptedNow();
    }

    @Test
    @DisplayName("한 번 소모한 허용은 다른 run이 다시 소모하지 못한다")
    void consumesOnlyOnce() {
      // given
      if (!ports.guards().consumeAcceptance(SOURCE, new RunId("run-a"), clock.instant()))
        throw new IllegalStateException("given 준비 실패: 처음 소모가 실패했다");

      // when
      boolean again = ports.guards().consumeAcceptance(SOURCE, new RunId("run-b"), clock.instant());

      // then
      assertThat(again).isFalse();
    }

    @Test
    @DisplayName("만료로 표시한 허용은 소모하지 못한다")
    void expiredIsNotConsumed() {
      // given
      if (!ports.guards().expireAcceptance(SOURCE, acceptedAt, clock.instant()))
        throw new IllegalStateException("given 준비 실패: 만료 표시가 실패했다");

      // when
      boolean consumed =
          ports.guards().consumeAcceptance(SOURCE, new RunId("run-a"), clock.instant());

      // then
      assertThat(consumed).isFalse();
    }

    @Test
    @DisplayName("다시 허용한 뒤 옛 허용 시각으로는 새 허용을 만료시키지 못한다")
    void staleExpiryLeavesNewAcceptance() {
      // given
      givenTimePassed(TTL);
      givenShrinkAcceptedNow();

      // when
      ports.guards().expireAcceptance(SOURCE, acceptedAt, clock.instant());

      // then
      assertThat(ports.guards().lastAcceptance(SOURCE))
          .hasValueSatisfying(a -> assertThat(a.pending()).isTrue());
    }
  }

  @Nested
  @DisplayName("축소를 허용한 소스의 run이 거부와 함께 끝나면")
  class AcceptedButRejected {
    @BeforeEach
    void givenAcceptedShrink() {
      givenActive(100);
      givenShrinkAccepted();
    }

    @Test
    @DisplayName("정리하지 않았으므로 허용 표시가 남는다")
    void keepsAcceptancePending() {
      // given
      RunId run = givenRunObserving(49);

      // when
      complete(run, RunStatus.SUCCEEDED_WITH_REJECTIONS);

      // then
      assertThat(ports.guards().lastAcceptance(SOURCE))
          .hasValueSatisfying(a -> assertThat(a.pending()).isTrue());
    }
  }

  @Nested
  @DisplayName("보호된 run 뒤 다음 run이 49건만 관측하지 못하면")
  class NormalRunAfterGuardedRun {
    CollectionRun next;

    @BeforeEach
    void givenGuardedThenNormalRun() {
      givenActive(100);
      completeSucceeded(givenRunObserving(0));
      if (ports.guards().lastGuarded(SOURCE).isEmpty())
        throw new IllegalStateException("given 준비 실패: 첫 run이 보호되지 않았다");
      RunId run = givenRunObserving(51);

      next = completeSucceeded(run);
    }

    @Test
    @DisplayName("다음 run은 그 49건을 지운다")
    void reconcilesNormally() {
      // then
      assertTombstoneCount(49);
    }

    @Test
    @DisplayName("보호 상태가 풀린다")
    void clearsGuardedState() {
      // then
      assertThat(ports.guards().lastGuarded(SOURCE)).isEmpty();
    }
  }

  @Nested
  @DisplayName("축소를 허용한 뒤 run이 정리 직전에 멈추고 다음 run이 끝나면")
  class StoppedBeforeReconcile {
    RunId next;

    @BeforeEach
    void givenStoppedRunThenNextRun() {
      givenActive(100);
      givenShrinkAccepted();
      givenRunObserving(0);
      givenRunStoppedBeforeCompletion();
      next = givenRunObserving(49);

      completeSucceeded(next);
    }

    @Test
    @DisplayName("다음 run이 허용 표시로 51건을 지운다")
    void nextRunUsesAcceptance() {
      // then
      assertTombstoneCount(51);
    }

    @Test
    @DisplayName("허용 표시는 멈춘 run이 아니라 다음 run이 소모한다")
    void acceptanceConsumedByNextRun() {
      // then
      assertThat(ports.guards().lastAcceptance(SOURCE))
          .hasValueSatisfying(a -> assertThat(a.consumedBy()).contains(next));
    }
  }
}
