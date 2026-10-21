package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.scos.collection.internal.SourceChangeLog.Change;
import io.scos.collection.internal.SourceChangeLog.Kind;
import io.scos.collection.internal.SourceChangeLog.Page;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** F10 변경 로그 계약: append 순서 = seq, 커서 읽기(after·limit·head), 소스 필터, 보존 하한과 만료. */
@DisplayName("변경 로그는 seq 순으로 쌓이고 커서로 읽힌다(F10)")
public abstract class SourceChangeLogContract {
  protected static final SourceInstanceId A = new SourceInstanceId("src-a");
  protected static final SourceInstanceId B = new SourceInstanceId("src-b");
  protected static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final int PAGE = 2;
  static final int READ_ALL = 10;
  static final int MANY = 1_001;

  /** x(T0)·y(T0+10s)·z(T0+20s) 사이의 prune 기준 시각. */
  static final Instant AFTER_X = T0.plusSeconds(5);

  static final Instant AFTER_Y = T0.plusSeconds(15);

  /** T0에 쓴 변경을 모두 지우는 경계. */
  static final Instant AFTER_ALL = T0.plusSeconds(1);

  protected SourceChangeLog log;

  protected abstract SourceChangeLog newLog();

  @BeforeEach
  void setUp() {
    log = newLog();
  }

  void append(SourceInstanceId source, String id, Kind kind, Instant at) {
    log.appendAll(
        List.of(
            new SourceChangeLog.Entry(
                source, Optional.ofNullable(id), kind, Optional.of("v1"), Optional.empty(), at)));
  }

  /** 소스 A에 x·y·z를 10초 간격으로(seq 1·2·3). */
  void givenThreeChanges() {
    append(A, "x", Kind.UPSERT, T0);
    append(A, "y", Kind.PROVIDER_DELETE, T0.plusSeconds(10));
    append(A, null, Kind.COLLECTION_INCOMPLETE, T0.plusSeconds(20));
  }

  static List<Long> seqs(Page page) {
    return page.changes().stream().map(Change::seq).toList();
  }

  Page readAfter(long after, int limit) {
    return log.read(Optional.empty(), after, limit);
  }

  // ---- 빈 로그와 커서 ----

  @Test
  @DisplayName("비어 있으면 head는 0이다")
  void emptyLogHeadIsZero() {
    // when
    long head = log.head();

    // then
    assertThat(head).isZero();
  }

  @Test
  @DisplayName("빈 로그를 처음부터 읽으면 커서가 0인 빈 페이지다")
  void emptyLog() {
    // when
    Page page = readAfter(0, READ_ALL);

    // then
    assertThat(page)
        .extracting(Page::changes, Page::nextSeq, Page::headSeq)
        .containsExactly(List.of(), 0L, 0L);
  }

  @Test
  @DisplayName("한 번에 많은 변경(1,001건)을 기록해도 넘긴 순서대로 seq가 붙는다")
  void appendAllKeepsOrderForManyEntries() {
    // when
    log.appendAll(
        IntStream.rangeClosed(1, MANY)
            .mapToObj(
                i ->
                    new SourceChangeLog.Entry(
                        A,
                        Optional.of("e" + i),
                        Kind.UPSERT,
                        Optional.empty(),
                        Optional.empty(),
                        T0))
            .toList());

    // then
    assertThat(log.head()).isEqualTo(MANY);
    assertThat(readAfter(MANY - 1, READ_ALL).changes().get(0).externalId()).contains("e" + MANY);
  }

  @ParameterizedTest(name = "after {0}, limit {1}")
  @CsvSource({"2, 10", "-1, 10", "0, 0", "0, 1001"})
  @DisplayName("head 밖의 커서나 범위 밖 limit은 IllegalArgumentException이다")
  void invalidCursor(long after, int limit) {
    // given
    append(A, "x", Kind.UPSERT, T0);

    // when·then
    assertThatThrownBy(() -> readAfter(after, limit)).isInstanceOf(IllegalArgumentException.class);
  }

  @Nested
  @DisplayName("소스 A에 x·y·z(seq 1·2·3)를 10초 간격으로 쓴 상태에서")
  class ThreeChanges {
    @BeforeEach
    void givenWritten() {
      givenThreeChanges();
    }

    @Test
    @DisplayName("append 순서대로 seq가 1부터 붙는다")
    void appendOrderIsSeq() {
      // when
      Page all = readAfter(0, READ_ALL);

      // then
      assertThat(seqs(all)).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("한 번에 limit개까지 읽는다")
    void readsUpToLimit() {
      // when
      Page first = readAfter(0, PAGE);

      // then
      assertThat(seqs(first)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("변경은 append한 종류를 그대로 담는다")
    void changesCarryKind() {
      // when
      Page first = readAfter(0, PAGE);

      // then
      assertThat(first.changes())
          .extracting(Change::kind)
          .containsExactly(Kind.UPSERT, Kind.PROVIDER_DELETE);
    }

    @Test
    @DisplayName("변경은 외부 id와 sourceVersion을 그대로 담는다")
    void changesCarryIdAndVersion() {
      // when
      Change first = readAfter(0, PAGE).changes().get(0);

      // then
      assertThat(first)
          .extracting(Change::externalId, Change::sourceVersion)
          .containsExactly(Optional.of("x"), Optional.of("v1"));
    }

    @Test
    @DisplayName("페이지는 마지막 seq를 nextSeq로, 전체 head를 headSeq로 준다")
    void pageCursors() {
      // when
      Page first = readAfter(0, PAGE);

      // then
      assertThat(first).extracting(Page::nextSeq, Page::headSeq).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("nextSeq부터 다음 페이지를 읽는다(외부 id 없는 변경 포함)")
    void readsFromNextSeq() {
      // given
      long next = readAfter(0, PAGE).nextSeq();

      // when
      Page second = readAfter(next, PAGE);

      // then
      assertThat(seqs(second)).containsExactly(3L);
      assertThat(second.changes().get(0).externalId()).isEmpty();
      assertThat(second.nextSeq()).isEqualTo(3);
    }

    @Test
    @DisplayName("head에서 읽으면 nextSeq가 head인 빈 페이지다")
    void readingAtHeadIsEmpty() {
      // given
      if (log.head() != 3) throw new IllegalStateException("given 준비 실패: head가 3이 아니다");

      // when
      Page end = readAfter(log.head(), PAGE);

      // then
      assertThat(end).extracting(Page::changes, Page::nextSeq).containsExactly(List.of(), 3L);
    }

    @Test
    @DisplayName("pruneBefore(cutoff)는 cutoff 이전 변경을 지운 수를 돌려준다")
    void pruneReturnsCount() {
      // when
      int pruned = log.pruneBefore(AFTER_Y);

      // then
      assertThat(pruned).isEqualTo(2);
    }

    @Test
    @DisplayName("prune해도 head는 그대로다")
    void pruneKeepsHead() {
      // when
      log.pruneBefore(AFTER_Y);

      // then
      assertThat(log.head()).isEqualTo(3);
    }

    @Test
    @DisplayName("지울 것이 없으면 prune은 0이다")
    void pruneNothing() {
      // when
      int pruned = log.pruneBefore(T0);

      // then
      assertThat(pruned).isZero();
    }
  }

  @Nested
  @DisplayName("A·B·A 순으로 변경을 쓴 상태에서 소스 A로 거르면")
  class InterleavedSources {
    @BeforeEach
    void givenInterleavedSources() {
      append(A, "x", Kind.UPSERT, T0);
      append(B, "y", Kind.UPSERT, T0);
      append(A, "z", Kind.UPSERT, T0);
    }

    @Test
    @DisplayName("그 소스의 변경만 전역 seq로 돌려준다")
    void sourceFilter() {
      // when
      Page a = log.read(Optional.of(A), 0, READ_ALL);

      // then
      assertThat(seqs(a)).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("headSeq는 전역 head다")
    void sourceFilterKeepsGlobalHead() {
      // when
      Page a = log.read(Optional.of(A), 0, READ_ALL);

      // then
      assertThat(a.headSeq()).isEqualTo(3);
    }
  }

  // ---- 보존 하한 ----

  @Nested
  @DisplayName("x·y·z 중 y까지(seq 2) prune한 상태에서")
  class PrunedThroughY {
    @BeforeEach
    void givenPruned() {
      givenThreeChanges();
      log.pruneBefore(AFTER_Y);
    }

    @Test
    @DisplayName("지운 범위 아래 커서는 ChangeCursorExpiredException이다")
    void cursorBelowFloorExpires() {
      // when·then
      assertThatThrownBy(() -> readAfter(1, READ_ALL))
          .isInstanceOf(ChangeCursorExpiredException.class);
    }

    @Test
    @DisplayName("만료 예외는 보존 하한(retainedAfter)과 head를 알려준다")
    void expiryCarriesFloorAndHead() {
      // when
      ChangeCursorExpiredException expired =
          catchThrowableOfType(ChangeCursorExpiredException.class, () -> readAfter(1, READ_ALL));

      // then
      assertThat(expired)
          .extracting(
              ChangeCursorExpiredException::retainedAfter, ChangeCursorExpiredException::head)
          .containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("보존 하한 커서부터는 남은 변경이 읽힌다")
    void cursorAtFloorReads() {
      // when
      Page remaining = readAfter(2, READ_ALL);

      // then
      assertThat(seqs(remaining)).containsExactly(3L);
    }
  }

  @Nested
  @DisplayName("x·y·z 중 x(seq 1)만 prune한 상태에서")
  class FirstPruned {
    @BeforeEach
    void givenFirstPrune() {
      givenThreeChanges();
      int pruned = log.pruneBefore(AFTER_X);
      if (pruned != 1)
        throw new IllegalStateException("given 준비 실패: 첫 prune은 seq 1까지여야 한다: " + pruned);
    }

    @Test
    @DisplayName("두 번째 prune은 그다음 변경을 지운다")
    void secondPruneDeletesNext() {
      // when
      int pruned = log.pruneBefore(AFTER_Y);

      // then
      assertThat(pruned).isEqualTo(1);
    }

    @Test
    @DisplayName("보존 하한은 prune마다 올라간다")
    void floorRisesOnEachPrune() {
      // given
      log.pruneBefore(AFTER_Y);

      // when
      ChangeCursorExpiredException expired =
          catchThrowableOfType(ChangeCursorExpiredException.class, () -> readAfter(1, READ_ALL));

      // then
      assertThat(expired.retainedAfter()).isEqualTo(2);
    }

    @Test
    @DisplayName("새 보존 하한에서 읽으면 남은 변경이 나온다")
    void readsFromNewFloor() {
      // given
      log.pruneBefore(AFTER_Y);

      // when
      Page remaining = readAfter(2, READ_ALL);

      // then
      assertThat(seqs(remaining)).containsExactly(3L);
    }
  }

  /**
   * seq는 커밋 순서지만 occurredAt은 잠금 전에 읽은 시계라 동시 커밋에서 어긋날 수 있다. 하한은 seq 접두라(TS pruneChanges(through)와
   * 같다) cutoff 이전인 가장 큰 seq까지 그 아래 전부를 지운다(ADR-0020 F10).
   */
  @Nested
  @DisplayName("seq 1이 seq 2보다 늦은 시각인 상태에서 둘 사이 시각으로 prune하면")
  class SeqAndTimeDisagree {
    @BeforeEach
    void givenOutOfOrderTimes() {
      append(A, "late", Kind.UPSERT, T0.plusSeconds(10));
      append(A, "early", Kind.UPSERT, T0);
      append(A, "z", Kind.UPSERT, T0.plusSeconds(20));
    }

    @Test
    @DisplayName("cutoff 이전인 가장 큰 seq(2) 아래의 늦은 변경까지 함께 지운다")
    void prunesLaterChangeBelowThatSeq() {
      // when
      int pruned = log.pruneBefore(AFTER_X);

      // then
      assertThat(pruned).isEqualTo(2);
    }

    @Test
    @DisplayName("보존 하한은 cutoff 이전인 가장 큰 seq(2)다")
    void floorIsLargestSeqBeforeCutoff() {
      // given
      log.pruneBefore(AFTER_X);

      // when
      ChangeCursorExpiredException expired =
          catchThrowableOfType(ChangeCursorExpiredException.class, () -> readAfter(1, READ_ALL));

      // then
      assertThat(expired.retainedAfter()).isEqualTo(2);
    }

    @Test
    @DisplayName("보존 하한에서 읽으면 그다음 변경만 남아 있다")
    void readsOnlyAfterFloor() {
      // given
      log.pruneBefore(AFTER_X);

      // when
      Page remaining = readAfter(2, READ_ALL);

      // then
      assertThat(seqs(remaining)).containsExactly(3L);
    }
  }

  @Nested
  @DisplayName("T0에 쓴 변경 둘을 전부 prune하면")
  class AllPruned {
    @BeforeEach
    void givenTwoChanges() {
      append(A, "x", Kind.UPSERT, T0);
      append(A, "y", Kind.UPSERT, T0);
    }

    @Test
    @DisplayName("지운 수를 돌려준다")
    void pruneAll() {
      // when
      int pruned = log.pruneBefore(AFTER_ALL);

      // then
      assertThat(pruned).isEqualTo(2);
    }

    @Test
    @DisplayName("head는 그대로다")
    void pruneAllKeepsHead() {
      // when
      log.pruneBefore(AFTER_ALL);

      // then
      assertThat(log.head()).isEqualTo(2);
    }

    @Test
    @DisplayName("head에서 읽으면 빈 페이지다")
    void readAtHeadAfterPruneAllIsEmpty() {
      // when
      log.pruneBefore(AFTER_ALL);

      // then
      assertThat(readAfter(2, READ_ALL).changes()).isEmpty();
    }

    @Test
    @DisplayName("head 아래에서 읽으면 만료다")
    void readBelowHeadAfterPruneAllExpires() {
      // when
      log.pruneBefore(AFTER_ALL);

      // then
      assertThatThrownBy(() -> readAfter(0, READ_ALL))
          .isInstanceOf(ChangeCursorExpiredException.class);
    }
  }
}
