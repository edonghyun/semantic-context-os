package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CheckpointConflictException;
import io.scos.collection.internal.ClaimLostException;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.PageCommitResult;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 불변식 2·5를 PostgreSQL 어댑터 조합으로 확인: 네 포트가 같은 DataSource를 공유할 때 페이지 커밋이 원자적이고, 늦은 worker·revision 충돌은
 * 저장 경계가 거부하며 아무것도 남기지 않는다.
 */
@DisplayName("페이지 커밋은 PostgreSQL 포트 조합에서도 원자적이다(불변식 2·5)")
@Tag("db")
class PostgresPageCommitTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final Duration TTL = Duration.ofSeconds(30);

  DataSource ds;
  PostgresCollectionConfiguration.Adapters adapters;
  MutableClock clock;

  @BeforeEach
  void setUp() {
    PostgresSupport.truncateAll();
    ds = PostgresSupport.dataSource();
    adapters = PostgresCollectionConfiguration.adapters(ds);
    clock = new MutableClock(Instant.parse("2026-10-13T00:00:00Z"));
  }

  static final RunId RUN = new RunId("run-1");

  PageCommit pageCommit(OutboxPort outbox) {
    return pageCommit(outbox, adapters.changes());
  }

  PageCommit pageCommit(OutboxPort outbox, SourceChangeLog changes) {
    return new PageCommit(
        new TransactionTemplate(new DataSourceTransactionManager(ds)),
        adapters.claims(),
        adapters.runs(),
        adapters.rawItems(),
        adapters.checkpoints(),
        outbox,
        clock,
        TestMetrics.collection(),
        changes,
        new Progress(clock));
  }

  RunId startRun() {
    adapters.runs().start(RUN, SOURCE, clock.instant());
    return RUN;
  }

  CollectionPage page(String... ids) {
    return CollectionPage.ofItems(
        Arrays.stream(ids)
            .map(id -> RawItem.minimal(id, "v1", "{\"id\":\"" + id + "\"}", clock.instant()))
            .toList(),
        new Checkpoint(1, "{\"after\":\"" + ids[ids.length - 1] + "\"}"));
  }

  WorkClaim claim(String owner) {
    return adapters
        .claims()
        .tryClaim(SOURCE, new OwnerId(owner), TTL, clock.instant())
        .orElseThrow();
  }

  PageCommit commits() {
    return pageCommit(adapters.outbox());
  }

  /** 지금 있는 투영 요청을 처리해 비운다 — 다음 커밋이 요청을 새로 남기는지 본다. */
  void givenProjectionDone() {
    adapters.outbox().complete(SOURCE, adapters.outbox().request(SOURCE).orElseThrow().version());
  }

  long rawCount() {
    return adapters.rawItems().count(SOURCE);
  }

  long revision() {
    return adapters.checkpoints().read(SOURCE).revision();
  }

  @Nested
  @DisplayName("w1이 점유한 run에서 정상 페이지를 커밋하면")
  class CommitsAtomically {
    WorkClaim claim;
    RunId run;

    @BeforeEach
    void givenClaimedRun() {
      claim = claim("w1");
      run = startRun();
    }

    @Test
    @DisplayName("새 revision과 upsert 수를 돌려준다")
    void returnsSummary() {
      // when
      PageCommitResult result = commits().commit(claim, run, 0, page("a", "b"));

      // then
      assertThat(result).isEqualTo(new PageCommitResult(1, 2, 0, 0, 0));
    }

    @Test
    @DisplayName("원본·체크포인트·투영 요청이 함께 커밋된다")
    void commitsItemsCheckpointAndRequest() {
      // when
      commits().commit(claim, run, 0, page("a", "b"));

      // then
      assertThat(rawCount()).isEqualTo(2);
      assertThat(revision()).isEqualTo(1);
      assertThat(adapters.outbox().pending()).containsExactly(SOURCE);
    }

    @Test
    @DisplayName("run에 페이지 수와 관측을 기록한다")
    void recordsOnRun() {
      // when
      commits().commit(claim, run, 0, page("a", "b"));

      // then
      assertThat(adapters.runs().get(RUN).orElseThrow().pagesApplied()).isEqualTo(1);
      assertThat(adapters.runs().observations(RUN)).containsExactlyInAnyOrder("a", "b");
    }
  }

  @Nested
  @DisplayName("마지막 단계(투영 요청)에서 실패하는 Outbox로 커밋하면")
  class LateFailure {
    final OutboxPort failingOutbox =
        new MemoryOutboxPort() {
          @Override
          public synchronized void requestProjection(SourceInstanceId source, Instant now) {
            throw new IllegalStateException("injected after checkpoint");
          }
        };

    WorkClaim claim;
    RunId run;

    @BeforeEach
    void givenClaimedRun() {
      claim = claim("w1");
      run = startRun();
    }

    Throwable commitFailingLate() {
      return catchThrowable(() -> pageCommit(failingOutbox).commit(claim, run, 0, page("a", "b")));
    }

    @Test
    @DisplayName("주입한 실패가 그대로 전파된다")
    void propagatesFailure() {
      // when·then
      assertThatThrownBy(() -> pageCommit(failingOutbox).commit(claim, run, 0, page("a", "b")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("injected after checkpoint");
    }

    @Test
    @DisplayName("주입한 실패로 끝나고, 원본과 체크포인트도 롤백된다")
    void rollsBackItemsAndCheckpoint() {
      // when
      Throwable thrown = commitFailingLate();

      // then: 다른 이유로 일찍 끝났으면 롤백 단언이 공짜로 통과하므로 주입한 실패인지부터 본다
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("injected after checkpoint");
      assertThat(rawCount()).isZero();
      assertThat(revision()).isZero();
    }

    @Test
    @DisplayName("주입한 실패로 끝나고, run의 페이지 수와 관측도 롤백된다")
    void rollsBackRunRecords() {
      // when
      Throwable thrown = commitFailingLate();

      // then
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("injected after checkpoint");
      assertThat(adapters.runs().get(RUN).orElseThrow().pagesApplied()).isZero();
      assertThat(adapters.runs().observations(RUN)).isEmpty();
    }
  }

  @Nested
  @DisplayName("마지막 작업(변경 로그 append)이 행을 쓴 뒤 실패하면")
  class ChangeLogFailure {
    WorkClaim claim;
    RunId run;

    @BeforeEach
    void givenClaimedRunAndFailedCommit() {
      claim = claim("w1");
      run = startRun();
      Throwable thrown =
          catchThrowable(
              () ->
                  pageCommit(adapters.outbox(), new FailingChangeLog(adapters.changes()))
                      .commit(claim, run, 0, page("a", "b")));
      if (!(thrown instanceof IllegalStateException e)
          || !e.getMessage().equals(FailingChangeLog.MESSAGE))
        throw new IllegalStateException("given 준비 실패: 커밋이 변경 로그에서 실패하지 않았다", thrown);
    }

    @Test
    @DisplayName("변경 로그(F10) 행이 남지 않는다")
    void leavesNoChangeRows() {
      // then
      assertThat(adapters.changes().read(Optional.of(SOURCE), 0, 10).changes()).isEmpty();
    }

    @Test
    @DisplayName("원본·체크포인트·투영 요청도 남지 않는다")
    void leavesNoItemsCheckpointOrRequest() {
      // then
      assertThat(rawCount()).isZero();
      assertThat(revision()).isZero();
      assertThat(adapters.outbox().pending()).isEmpty();
    }
  }

  @Nested
  @DisplayName("점유가 만료돼 다른 worker에게 넘어간 뒤 옛 worker가 커밋하면")
  class StaleWorker {
    WorkClaim stale;
    RunId run;

    /** w1이 a를, 만료 뒤 점유한 w2가 b를 커밋하고 투영까지 끝난 상태. */
    @BeforeEach
    void givenTakenOverAfterExpiry() {
      stale = claim("w1");
      run = startRun();
      commits().commit(stale, run, 0, page("a"));
      clock.advance(TTL.plusSeconds(1));
      commits().commit(claim("w2"), run, 1, page("b"));
      givenProjectionDone();
    }

    @Test
    @DisplayName("ClaimLostException으로 거부된다")
    void rejectsStaleWorker() {
      // when·then
      assertThatThrownBy(() -> commits().commit(stale, run, 2, page("late")))
          .isInstanceOf(ClaimLostException.class);
    }

    @Test
    @DisplayName("거부되고, 원본도 체크포인트도 남기지 않는다")
    void writesNothing() {
      // when
      Throwable rejected = catchThrowable(() -> commits().commit(stale, run, 2, page("late")));

      // then
      assertThat(rejected).isInstanceOf(ClaimLostException.class);
      assertThat(rawCount()).isEqualTo(2);
      assertThat(revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("거부되고, 투영 요청을 남기지 않는다")
    void requestsNothing() {
      // when
      Throwable rejected = catchThrowable(() -> commits().commit(stale, run, 2, page("late")));

      // then
      assertThat(rejected).isInstanceOf(ClaimLostException.class);
      assertThat(adapters.outbox().pending()).isEmpty();
    }
  }

  @Nested
  @DisplayName("해제 뒤 다시 점유된 소스에 만료 전 옛 worker가 커밋하면")
  class SupersededWorker {
    WorkClaim old;
    WorkClaim current;
    RunId run;

    /** w1이 a를 커밋하고 해제한 뒤 같은 소스를 다시 점유했다. 옛 점유는 시각상 아직 유효하다. */
    @BeforeEach
    void givenReclaimedBeforeExpiry() {
      old = claim("w1");
      run = startRun();
      commits().commit(old, run, 0, page("a"));
      adapters.claims().release(old, clock.instant());
      current = claim("w1");
      if (!old.isActiveAt(clock.instant()))
        throw new IllegalStateException("given 준비 실패: 옛 점유는 시각상 아직 유효해야 한다");
      givenProjectionDone();
    }

    Throwable commitOld() {
      return catchThrowable(() -> commits().commit(old, run, 1, page("late")));
    }

    @Test
    @DisplayName("ClaimLostException으로 거부된다")
    void rejectsOldWorker() {
      // when·then
      assertThatThrownBy(() -> commits().commit(old, run, 1, page("late")))
          .isInstanceOf(ClaimLostException.class);
    }

    @Test
    @DisplayName("거부되고, 원본을 남기지 않는다")
    void writesNothing() {
      // when
      Throwable rejected = commitOld();

      // then
      assertThat(rejected).isInstanceOf(ClaimLostException.class);
      assertThat(rawCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("거부되고, 체크포인트를 전진하지 않는다")
    void keepsRevision() {
      // when
      Throwable rejected = commitOld();

      // then
      assertThat(rejected).isInstanceOf(ClaimLostException.class);
      assertThat(revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("거부되고, 투영 요청을 남기지 않는다")
    void requestsNoProjection() {
      // when
      Throwable rejected = commitOld();

      // then
      assertThat(rejected).isInstanceOf(ClaimLostException.class);
      assertThat(adapters.outbox().pending()).isEmpty();
    }

    @Test
    @DisplayName("옛 worker가 거부된 뒤 현재 점유는 같은 revision으로 커밋한다")
    void currentClaimCommits() {
      // given
      if (!(commitOld() instanceof ClaimLostException))
        throw new IllegalStateException("given 준비 실패: 옛 worker가 ClaimLostException으로 거부돼야 한다");

      // when
      commits().commit(current, run, 1, page("b"));

      // then
      assertThat(rawCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("첫 페이지를 커밋한 점유가 어긋난 revision으로 다시 커밋하면")
  class RevisionConflict {
    WorkClaim claim;
    RunId run;

    @BeforeEach
    void givenFirstPageCommitted() {
      claim = claim("w1");
      run = startRun();
      commits().commit(claim, run, 0, page("a"));
    }

    @Test
    @DisplayName("CheckpointConflictException으로 거부된다")
    void rejectsRevisionConflict() {
      // when·then
      assertThatThrownBy(() -> commits().commit(claim, run, 0, page("dup")))
          .isInstanceOf(CheckpointConflictException.class);
    }

    @Test
    @DisplayName("거부되고, 원본과 체크포인트는 롤백된다")
    void revisionConflictRollsBackItems() {
      // when
      Throwable rejected = catchThrowable(() -> commits().commit(claim, run, 0, page("dup")));

      // then
      assertThat(rejected).isInstanceOf(CheckpointConflictException.class);
      assertThat(rawCount()).isEqualTo(1);
      assertThat(revision()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("투영 요청이 backoff 중일 때 같은 페이지(전부 unchanged)를 다시 커밋하면")
  class UnchangedPage {
    WorkClaim claim;
    RunId run;
    long backedOffVersion;

    /** a를 커밋하고 그 투영 요청이 실패해 backoff 중인 상태. */
    @BeforeEach
    void givenBackedOffAfterFirstPage() {
      claim = claim("w1");
      run = startRun();
      commits().commit(claim, run, 0, page("a"));
      backedOffVersion = adapters.outbox().request(SOURCE).orElseThrow().version();
      adapters
          .outbox()
          .recordFailure(
              SOURCE, backedOffVersion, clock.instant(), "x", ProjectionRetryPolicy.defaults());
      if (!adapters.outbox().due(clock.instant()).isEmpty())
        throw new IllegalStateException("given 준비 실패: 투영 요청이 backoff 중이어야 한다");
    }

    PageCommitResult recommitSamePage() {
      return commits().commit(claim, run, 1, page("a"));
    }

    @Test
    @DisplayName("upsert 없이 unchanged로만 센다")
    void countsUnchanged() {
      // when
      PageCommitResult result = recommitSamePage();

      // then
      assertThat(result)
          .extracting(PageCommitResult::unchanged, PageCommitResult::upserted)
          .containsExactly(1, 0);
    }

    @Test
    @DisplayName("투영 요청을 새로 등록하지 않는다")
    void keepsRequestVersion() {
      // when
      recommitSamePage();

      // then
      assertThat(adapters.outbox().request(SOURCE).orElseThrow().version())
          .isEqualTo(backedOffVersion);
    }

    @Test
    @DisplayName("기존 요청의 backoff를 풀지 않는다")
    void keepsBackoff() {
      // when
      recommitSamePage();

      // then
      assertThat(adapters.outbox().due(clock.instant())).isEmpty();
    }

    @Test
    @DisplayName("체크포인트는 전진한다")
    void advancesCheckpoint() {
      // when
      recommitSamePage();

      // then
      assertThat(revision()).isEqualTo(2);
    }
  }
}
