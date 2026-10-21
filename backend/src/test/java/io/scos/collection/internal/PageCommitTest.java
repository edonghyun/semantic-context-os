package io.scos.collection.internal;

import static io.scos.collection.internal.RawItems.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceChangeLog;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 페이지 커밋 유스케이스(F2)의 판단 로직을 메모리 포트로 검증한다: 검사 순서(점유 → run → 적용), 결과 집계, 투영 요청 조건. 원자성(롤백)은 {@code
 * PostgresPageCommitTest}의 몫이다.
 */
@DisplayName("페이지 커밋은 점유와 run을 검사한 뒤 한 페이지를 적용한다(F2)")
class PageCommitTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final RunId RUN = new RunId("run-1");
  static final Duration TTL = Duration.ofSeconds(30);

  MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  MemoryRunStore runs = new MemoryRunStore();
  MemoryRawItemStore rawItems = new MemoryRawItemStore();
  MemoryCheckpointPort checkpoints = new MemoryCheckpointPort();
  MemoryOutboxPort outbox = new MemoryOutboxPort();
  MemorySourceChangeLog changes = new MemorySourceChangeLog();
  Progress progress = new Progress(clock);
  PageCommit commit =
      new PageCommit(
          TransactionOperations.withoutTransaction(),
          claims,
          runs,
          rawItems,
          checkpoints,
          outbox,
          clock,
          TestMetrics.collection(),
          changes,
          progress);
  WorkClaim claim;

  @BeforeEach
  void claimAndStartRun() {
    claim =
        claims
            .tryClaim(SOURCE, new OwnerId("w1"), TTL, clock.instant())
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: w1이 점유하지 못했다"));
    runs.start(RUN, SOURCE, clock.instant());
  }

  CollectionPage page(String... ids) {
    return CollectionPage.ofItems(
        Arrays.stream(ids).map(id -> item(id).build()).toList(),
        new Checkpoint(1, "{\"after\":\"" + ids[ids.length - 1] + "\"}"));
  }

  static RawMutation.Delete delete(String id, Optional<String> version, Instant at) {
    return new RawMutation.Delete(id, version, Optional.empty(), Optional.empty(), at);
  }

  static RejectedItem rejection(Instant at) {
    return new RejectedItem(
        RejectedItem.FailureKind.INVALID_ITEM, "no ts", Optional.empty(), at, Optional.empty());
  }

  /** 첫 페이지 뒤 투영 요청을 처리해 비운 상태 — 다음 페이지가 요청을 새로 만드는지 본다. */
  void givenFirstPageProjected(String... ids) {
    commit.commit(claim, RUN, 0, page(ids));
    OutboxPort.Request request =
        outbox
            .request(SOURCE)
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: 첫 페이지가 투영을 요청하지 않았다"));
    outbox.complete(SOURCE, request.version());
  }

  /**
   * 거부될 커밋을 시도한다. 거부 자체는 다른 테스트의 몫이다 — 그 종류로 거부되지 않으면 given 준비 실패로 멈춰, 무효과 단언이 거부 없이 통과하지 않게 한다.
   */
  static void attemptRejected(ThrowingCallable attempt, Class<? extends Throwable> expected) {
    Throwable rejected = catchThrowable(attempt);
    if (!expected.isInstance(rejected)) {
      throw new IllegalStateException(
          "given 준비 실패: 커밋이 " + expected.getSimpleName() + "로 거부되지 않았다", rejected);
    }
  }

  @Test
  @DisplayName("페이지 커밋은 작업 진행 신호를 그 시각으로 남긴다")
  void pageCommitMarksProgress() {
    // when
    commit.commit(claim, RUN, 0, page("a"));

    // then
    assertThat(progress.lastAt()).contains(clock.instant());
  }

  @Test
  @DisplayName("변경 로그에는 upsert와 provider_delete만 sourceVersion과 함께 남는다(F10)")
  void appendsChangeLogForUpsertAndDeleteOnly() {
    // given: a·b를 새로 수집한 뒤 같은 버전의 a를 다시 수집했다(unchanged)
    commit.commit(claim, RUN, 0, page("a", "b"));
    commit.commit(claim, RUN, 1, page("a"));
    CollectionPage deleteB =
        new CollectionPage(
            List.of(delete("b", Optional.of("v1"), clock.instant())),
            List.of(),
            new Checkpoint(1, "{\"after\":\"b\"}"));

    // when
    commit.commit(claim, RUN, 2, deleteB);

    // then
    assertThat(changes.all())
        .extracting(
            c ->
                c.kind().wire()
                    + ":"
                    + c.externalId().orElse("-")
                    + ":"
                    + c.sourceVersion().orElse("-"))
        .containsExactly("upsert:a:v1", "upsert:b:v1", "provider_delete:b:v1");
  }

  @Nested
  @DisplayName("정상 페이지(a·b)를 커밋하면")
  class AppliesPage {
    @Test
    @DisplayName("새 revision과 upsert 수를 돌려준다")
    void returnsSummary() {
      // when
      PageCommitResult result = commit.commit(claim, RUN, 0, page("a", "b"));

      // then
      assertThat(result).isEqualTo(new PageCommitResult(1, 2, 0, 0, 0));
    }

    @Test
    @DisplayName("원본을 적용한다")
    void appliesItems() {
      // when
      commit.commit(claim, RUN, 0, page("a", "b"));

      // then
      assertThat(rawItems.count(SOURCE)).isEqualTo(2);
    }

    @Test
    @DisplayName("run에 관측을 기록한다")
    void recordsObservations() {
      // when
      commit.commit(claim, RUN, 0, page("a", "b"));

      // then
      assertThat(runs.observations(RUN)).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("run 카운터(페이지·upsert)를 올린다")
    void countsOnRun() {
      // when
      commit.commit(claim, RUN, 0, page("a", "b"));

      // then
      assertThat(runs.get(RUN))
          .get()
          .extracting(CollectionRun::pagesApplied, CollectionRun::upserted)
          .containsExactly(1, 2);
    }

    @Test
    @DisplayName("체크포인트를 한 revision 전진한다")
    void advancesCheckpoint() {
      // when
      commit.commit(claim, RUN, 0, page("a", "b"));

      // then
      assertThat(checkpoints.read(SOURCE).revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("투영을 요청한다")
    void requestsProjection() {
      // when
      commit.commit(claim, RUN, 0, page("a", "b"));

      // then
      assertThat(outbox.pending()).containsExactly(SOURCE);
    }
  }

  @Nested
  @DisplayName("a를 커밋해 투영까지 마친 상태에서")
  class AfterFirstPageProjected {
    @BeforeEach
    void givenAProjected() {
      givenFirstPageProjected("a");
    }

    @Test
    @DisplayName("같은 sourceVersion을 다시 수집하면 unchanged로 센다")
    void countsUnchanged() {
      // when
      PageCommitResult result = commit.commit(claim, RUN, 1, page("a"));

      // then
      assertThat(result).isEqualTo(new PageCommitResult(2, 0, 0, 1, 0));
    }

    @Test
    @DisplayName("같은 sourceVersion을 다시 수집하면 투영을 요청하지 않는다")
    void requestsNoProjection() {
      // when
      commit.commit(claim, RUN, 1, page("a"));

      // then
      assertThat(outbox.pending()).isEmpty();
    }

    @Test
    @DisplayName("삭제만 있는 페이지는 deleted 1로 세고, 투영을 요청한다")
    void deleteOnlyPageRequestsProjection() {
      // given
      CollectionPage deleteOnly =
          new CollectionPage(
              List.of(delete("a", Optional.empty(), clock.instant())),
              List.of(),
              new Checkpoint(1, "{\"after\":\"a\"}"));

      // when
      PageCommitResult result = commit.commit(claim, RUN, 1, deleteOnly);

      // then
      assertThat(result.deleted()).isEqualTo(1);
      assertThat(outbox.pending()).containsExactly(SOURCE);
    }

    @Test
    @DisplayName("upsert 1 + delete 1 페이지는 둘 다 세고, 투영을 요청한다")
    void mixedChangesRequestProjection() {
      // given
      CollectionPage mixed =
          new CollectionPage(
              List.of(
                  new RawMutation.Upsert(item("b").build()),
                  delete("a", Optional.empty(), clock.instant())),
              List.of(),
              new Checkpoint(1, "{\"after\":\"b\"}"));

      // when
      PageCommitResult result = commit.commit(claim, RUN, 1, mixed);

      // then
      assertThat(result).isEqualTo(new PageCommitResult(2, 1, 1, 0, 0));
      assertThat(outbox.pending()).containsExactly(SOURCE);
    }
  }

  @Nested
  @DisplayName("a·b를 커밋한 뒤 a 삭제와 거부 하나를 담은 페이지를 커밋하면")
  class DeletesAndRejections {
    @BeforeEach
    void givenAAndBCommitted() {
      commit.commit(claim, RUN, 0, page("a", "b"));
    }

    PageCommitResult commitDeleteAndRejection() {
      return commit.commit(
          claim,
          RUN,
          1,
          new CollectionPage(
              List.of(delete("a", Optional.empty(), clock.instant())),
              List.of(rejection(clock.instant())),
              new Checkpoint(1, "{\"after\":\"b\"}")));
    }

    @Test
    @DisplayName("deleted·rejected를 센다")
    void countsDeletesAndRejections() {
      // when
      PageCommitResult result = commitDeleteAndRejection();

      // then
      assertThat(result).isEqualTo(new PageCommitResult(2, 0, 1, 0, 1));
    }

    @Test
    @DisplayName("삭제는 원본을 tombstone으로 바꾼다")
    void tombstonesDeleted() {
      // when
      commitDeleteAndRejection();

      // then
      assertThat(rawItems.count(SOURCE)).isEqualTo(1);
      assertThat(rawItems.tombstoneCount(SOURCE)).isEqualTo(1);
    }

    @Test
    @DisplayName("거부 항목을 run에 남긴다")
    void recordsRejection() {
      // when
      commitDeleteAndRejection();

      // then
      assertThat(runs.rejections(RUN)).hasSize(1);
    }
  }

  @Nested
  @DisplayName("점유를 다른 worker가 가져갔으면")
  class LostClaim {
    @BeforeEach
    void givenClaimTakenByOtherWorker() {
      claims.release(claim, clock.instant());
      if (claims.tryClaim(SOURCE, new OwnerId("w2"), TTL, clock.instant()).isEmpty()) {
        throw new IllegalStateException("given 준비 실패: 다른 worker가 점유하지 못했다");
      }
    }

    void attemptCommit() {
      attemptRejected(() -> commit.commit(claim, RUN, 0, page("a")), ClaimLostException.class);
    }

    @Test
    @DisplayName("ClaimLostException이다")
    void rejectsLostClaim() {
      // when·then
      assertThatThrownBy(() -> commit.commit(claim, RUN, 0, page("a")))
          .isInstanceOf(ClaimLostException.class);
    }

    @Test
    @DisplayName("원본을 기록하지 않는다")
    void writesNoItems() {
      // when
      attemptCommit();

      // then
      assertThat(rawItems.count(SOURCE)).isZero();
    }

    @Test
    @DisplayName("관측을 기록하지 않는다")
    void recordsNoObservations() {
      // when
      attemptCommit();

      // then
      assertThat(runs.observations(RUN)).isEmpty();
    }

    @Test
    @DisplayName("체크포인트를 전진하지 않는다")
    void advancesNothing() {
      // when
      attemptCommit();

      // then
      assertThat(checkpoints.read(SOURCE).revision()).isZero();
    }

    @Test
    @DisplayName("투영을 요청하지 않는다")
    void requestsNoProjection() {
      // when
      attemptCommit();

      // then
      assertThat(outbox.pending()).isEmpty();
    }
  }

  @Nested
  @DisplayName("run이 running이 아니면")
  class RunNotRunning {
    @BeforeEach
    void givenRunSuperseded() {
      runs.start(new RunId("run-2"), SOURCE, clock.instant()); // run-1은 stale_run으로 마감된다
    }

    @Test
    @DisplayName("RunStateException이다")
    void rejectsNonRunningRun() {
      // when·then
      assertThatThrownBy(() -> commit.commit(claim, RUN, 0, page("a")))
          .isInstanceOf(RunStateException.class);
    }

    @Test
    @DisplayName("원본을 적용하지 않는다")
    void appliesNoItems() {
      // when
      attemptRejected(() -> commit.commit(claim, RUN, 0, page("a")), RunStateException.class);

      // then
      assertThat(rawItems.count(SOURCE)).isZero();
    }
  }

  @Nested
  @DisplayName("체크포인트가 revision 1로 전진한 뒤 기대 revision 0으로 커밋하면")
  class RevisionConflict {
    @BeforeEach
    void givenFirstPageCommitted() {
      commit.commit(claim, RUN, 0, page("a"));
    }

    @Test
    @DisplayName("CheckpointConflictException이다")
    void rejectsRevisionConflict() {
      // when·then
      assertThatThrownBy(() -> commit.commit(claim, RUN, 0, page("dup")))
          .isInstanceOf(CheckpointConflictException.class);
    }

    @Test
    @DisplayName("체크포인트를 전진하지 않는다")
    void revisionConflictKeepsCheckpoint() {
      // when: 이미 적용한 원본의 취소는 트랜잭션(어댑터 조합)의 몫 — PostgresPageCommitTest
      attemptRejected(
          () -> commit.commit(claim, RUN, 0, page("dup")), CheckpointConflictException.class);

      // then
      assertThat(checkpoints.read(SOURCE).revision()).isEqualTo(1);
    }
  }
}
