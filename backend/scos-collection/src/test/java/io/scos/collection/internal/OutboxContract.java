package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Outbox 포트 계약: 소스당 1행, 등록 순서, 완료 시 제거, version 조건 완료·실패, backoff·parked·요약. 원자성은 PageCommit 통합
 * 테스트가 본다.
 */
@DisplayName("투영 요청(Outbox)은 소스당 한 행이고 version 조건으로 끝난다")
public abstract class OutboxContract {
  protected static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
  static final SourceInstanceId A = new SourceInstanceId("a");
  static final SourceInstanceId B = new SourceInstanceId("b");
  static final SourceInstanceId C = new SourceInstanceId("c");
  static final ProjectionRetryPolicy POLICY =
      new ProjectionRetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 3);
  static final Instant FIRST_BACKOFF_END = NOW.plusSeconds(10);
  static final Instant SECOND_BACKOFF_END = FIRST_BACKOFF_END.plusSeconds(20);
  static final Instant LONG_AFTER = NOW.plus(Duration.ofDays(1));
  static final Instant NEW_DATA = NOW.plusSeconds(100);

  protected OutboxPort port;

  protected abstract OutboxPort newPort();

  @BeforeEach
  void setUpContract() {
    port = newPort();
  }

  long version(SourceInstanceId source) {
    return port.request(source).orElseThrow().version();
  }

  OutboxPort.Request request(SourceInstanceId source) {
    return port.request(source).orElseThrow();
  }

  /** 요청 하나를 등록하고 그 version을 돌려준다(투영 시작 시 읽은 값). */
  long givenRequested(SourceInstanceId source) {
    port.requestProjection(source, NOW);
    return version(source);
  }

  /** maxAttempts만큼 실패해 parked된 요청. */
  void givenParked(SourceInstanceId source) {
    long seen = givenRequested(source);
    for (int i = 0; i < POLICY.maxAttempts(); i++)
      port.recordFailure(source, seen, NOW, "x", POLICY);
    if (!request(source).parked())
      throw new IllegalStateException("given 준비 실패: " + source + " 요청이 parked가 아니다");
  }

  // ---- 대기 목록 ----

  @Test
  @DisplayName("같은 소스를 여러 번 요청해도 대기 목록에는 한 번만 있다")
  void coalescesRequestsPerSource() {
    // when
    port.requestProjection(A, NOW);
    port.requestProjection(A, NOW.plusSeconds(1));

    // then
    assertThat(port.pending()).containsExactly(A);
  }

  @Test
  @DisplayName("대기 목록은 요청 시각 순이다")
  void pendingIsOrderedByRequestTime() {
    // when
    port.requestProjection(B, NOW);
    port.requestProjection(A, NOW.plusSeconds(5));

    // then
    assertThat(port.pending()).containsExactly(B, A);
  }

  @Test
  @DisplayName("완료하면 true를 돌려주고, 대기 목록에서 빠진다")
  void completedSourceLeaves() {
    // given
    port.requestProjection(B, NOW);
    port.requestProjection(A, NOW.plusSeconds(5));

    // when
    boolean deleted = port.complete(B, version(B));

    // then
    assertThat(deleted).isTrue();
    assertThat(port.pending()).containsExactly(A);
  }

  @Test
  @DisplayName("요청 시각이 같으면 소스 id 코드 유닛 순이다")
  void sameInstantOrdersBySourceId() {
    // given
    SourceInstanceId z = new SourceInstanceId("z-first");
    SourceInstanceId a = new SourceInstanceId("a-second");

    // when
    port.requestProjection(z, NOW);
    port.requestProjection(a, NOW);

    // then
    assertThat(port.pending()).containsExactly(a, z);
  }

  @Nested
  @DisplayName("A, B 순으로 요청한 상태에서 A를 다시 요청하면")
  class ReRequestedFirst {
    @BeforeEach
    void givenAThenB() {
      port.requestProjection(A, NOW);
      port.requestProjection(B, NOW.plusSeconds(1));
      if (!port.pending().equals(List.of(A, B)))
        throw new IllegalStateException("given 준비 실패: 첫 요청 순이 A, B가 아니다");
    }

    @Test
    @DisplayName("요청 시각을 갱신해 대기 순서가 뒤로 간다")
    void reRequestMovesSourceToTheBack() {
      // when
      port.requestProjection(A, NOW.plusSeconds(2));

      // then
      assertThat(port.pending()).containsExactly(B, A);
    }

    @Test
    @DisplayName("due 순서에서도 뒤로 간다")
    void reRequestMovesSourceToTheBackOfDue() {
      // when
      port.requestProjection(A, NOW.plusSeconds(2));

      // then
      assertThat(port.due(NOW.plusSeconds(2))).containsExactly(B, A);
    }
  }

  // ---- version 조건 ----

  @Test
  @DisplayName("재요청마다 version이 하나 오른다")
  void reRequestBumpsVersion() {
    // given
    long seen = givenRequested(A);

    // when
    port.requestProjection(A, NOW.plusSeconds(1));

    // then
    assertThat(version(A)).isEqualTo(seen + 1);
  }

  @Test
  @DisplayName("없는 요청의 실패 기록은 무시된다")
  void failureOfMissingRequestIsIgnored() {
    // when
    boolean recorded =
        port.recordFailure(new SourceInstanceId("missing"), 1, NOW, "ignored", POLICY);

    // then
    assertThat(recorded).isFalse();
  }

  @Nested
  @DisplayName("A 요청을 읽어 처리하는 중에 새 요청이 들어온 상태에서")
  class ReRequestedAfterSeen {
    /** 처음 읽은(투영 시작 시) version. */
    long seen;

    @BeforeEach
    void givenReRequestedAfterSeen() {
      seen = givenRequested(A);
      port.requestProjection(A, NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("시작 시 읽은 version의 완료는 false다")
    void staleCompleteReportsNoDeletion() {
      // when
      boolean deleted = port.complete(A, seen);

      // then
      assertThat(deleted).isFalse();
    }

    @Test
    @DisplayName("시작 시 읽은 version의 완료는 지우지 않는다")
    void staleCompleteKeepsNewRequest() {
      // when
      port.complete(A, seen);

      // then
      assertThat(port.pending()).containsExactly(A);
    }

    @Test
    @DisplayName("최신 version의 완료는 true를 돌려준다")
    void currentCompleteReportsDeletion() {
      // given
      long current = version(A);

      // when
      boolean deleted = port.complete(A, current);

      // then
      assertThat(deleted).isTrue();
    }

    @Test
    @DisplayName("최신 version의 완료는 요청을 지운다")
    void currentCompleteDeletes() {
      // given
      long current = version(A);

      // when
      port.complete(A, current);

      // then
      assertThat(port.pending()).isEmpty();
    }

    @Test
    @DisplayName("옛 version의 실패 기록은 false다")
    void staleFailureReportsNotRecorded() {
      // when
      boolean recorded = port.recordFailure(A, seen, NOW.plusSeconds(2), "late", POLICY);

      // then
      assertThat(recorded).isFalse();
    }

    @Test
    @DisplayName("옛 version의 실패 기록은 attempts를 올리지 않는다")
    void staleFailureKeepsAttempts() {
      // when
      port.recordFailure(A, seen, NOW.plusSeconds(2), "late", POLICY);

      // then
      assertThat(request(A).attempts()).isZero();
    }

    @Test
    @DisplayName("옛 version의 실패 기록은 backoff를 씌우지 않는다")
    void staleFailureIsIgnored() {
      // when
      port.recordFailure(A, seen, NOW.plusSeconds(2), "late", POLICY);

      // then
      assertThat(port.due(NOW.plusSeconds(2))).containsExactly(A);
    }
  }

  // ---- 실패·backoff·parked ----

  @Nested
  @DisplayName("A가 요청된 상태에서 현재 version의 실패를 기록하면")
  class FailureOfCurrentVersion {
    long seen;

    @BeforeEach
    void givenRequestedA() {
      seen = givenRequested(A);
    }

    @Test
    @DisplayName("true를 돌려준다")
    void failureReportsRecorded() {
      // when
      boolean recorded = port.recordFailure(A, seen, NOW, "1 document ingestion(s) failed", POLICY);

      // then
      assertThat(recorded).isTrue();
    }

    @Test
    @DisplayName("attempts를 하나 올린다")
    void failureRecordsAttempt() {
      // when
      port.recordFailure(A, seen, NOW, "1 document ingestion(s) failed", POLICY);

      // then
      assertThat(request(A).attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("첫 backoff와 last_error를 남긴다")
    void failureRecordsBackoffAndError() {
      // when
      port.recordFailure(A, seen, NOW, "1 document ingestion(s) failed", POLICY);

      // then
      assertThat(request(A))
          .extracting(OutboxPort.Request::nextAttemptAt, OutboxPort.Request::lastError)
          .containsExactly(
              Optional.of(FIRST_BACKOFF_END), Optional.of("1 document ingestion(s) failed"));
    }
  }

  @Nested
  @DisplayName("A가 한 번 실패해 첫 backoff 중인 상태에서")
  class FailedOnce {
    long seen;

    @BeforeEach
    void givenFailedOnce() {
      seen = givenRequested(A);
      port.recordFailure(A, seen, NOW, "x", POLICY);
    }

    @Test
    @DisplayName("backoff가 끝나기 전에는 due가 아니다")
    void notDueDuringBackoff() {
      // when
      List<SourceInstanceId> due = port.due(NOW);

      // then
      assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("backoff가 끝나면 due다")
    void dueAfterBackoff() {
      // when
      List<SourceInstanceId> due = port.due(FIRST_BACKOFF_END);

      // then
      assertThat(due).containsExactly(A);
    }

    @Test
    @DisplayName("maxAttempts 전의 실패는 parked로 만들지 않는다")
    void failureBelowMaxAttemptsDoesNotPark() {
      // when
      OutboxPort.Request r = request(A);

      // then
      assertThat(r.parked()).isFalse();
    }

    @Test
    @DisplayName("둘째 실패의 backoff는 base·2^(n−1)로 늘어난다")
    void backoffGrows() {
      // when
      port.recordFailure(A, seen, FIRST_BACKOFF_END, "again", POLICY);

      // then
      assertThat(request(A).nextAttemptAt()).contains(SECOND_BACKOFF_END);
    }
  }

  @Nested
  @DisplayName("A가 maxAttempts만큼 실패해 parked된 상태에서")
  class Parked {
    @BeforeEach
    void givenParkedA() {
      givenParked(A);
    }

    @Test
    @DisplayName("parked 요청은 대기 목록에 남는다")
    void parkedIsPending() {
      // when
      List<SourceInstanceId> pending = port.pending();

      // then
      assertThat(pending).containsExactly(A);
    }

    @Test
    @DisplayName("parked 요청은 시간이 지나도 due가 아니다")
    void parkedIsNeverDue() {
      // when
      List<SourceInstanceId> due = port.due(LONG_AFTER);

      // then
      assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("새 요청은 parked와 backoff를 푼다")
    void newRequestUnparks() {
      // when
      port.requestProjection(A, NEW_DATA);

      // then
      assertThat(request(A))
          .extracting(OutboxPort.Request::parked, OutboxPort.Request::nextAttemptAt)
          .containsExactly(false, Optional.empty());
    }

    @Test
    @DisplayName("새 요청은 attempts를 0으로 돌린다")
    void newRequestResetsAttempts() {
      // when
      port.requestProjection(A, NEW_DATA);

      // then
      assertThat(request(A).attempts()).isZero();
    }

    @Test
    @DisplayName("새 요청 뒤에는 바로 due다")
    void newRequestIsDueAtOnce() {
      // when
      port.requestProjection(A, NEW_DATA);

      // then
      assertThat(port.due(NEW_DATA)).containsExactly(A);
    }
  }

  // ---- 연속 실패 수 ----

  @Nested
  @DisplayName("A가 두 번 실패한 뒤 새 데이터로 재요청된 상태에서")
  class ReRequestedAfterTwoFailures {
    OutboxPort.Request r;

    @BeforeEach
    void givenReRequestedAfterTwoFailures() {
      port.requestProjection(A, NOW);
      port.recordFailure(A, version(A), NOW, "x", POLICY);
      port.recordFailure(A, version(A), NOW, "x", POLICY);
      if (request(A).consecutiveFailures() != 2)
        throw new IllegalStateException("given 준비 실패: 연속 실패 수가 2가 아니다");
      port.requestProjection(A, NOW.plusSeconds(1));
      r = request(A);
    }

    @Test
    @DisplayName("재요청은 attempts를 0으로 돌린다")
    void reRequestResetsAttempts() {
      // then
      assertThat(r.attempts()).isZero();
    }

    @Test
    @DisplayName("재요청해도 연속 실패 수는 유지된다")
    void reRequestKeepsConsecutiveFailures() {
      // then
      assertThat(r.consecutiveFailures()).isEqualTo(2);
    }

    @Test
    @DisplayName("연속 실패 수는 version과 무관하게 실패마다 오른다")
    void consecutiveFailuresGrowAcrossVersions() {
      // when
      port.recordFailure(A, r.version(), NOW.plusSeconds(2), "x", POLICY);

      // then
      assertThat(request(A).consecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("완료 뒤 새 요청의 연속 실패 수는 0에서 시작한다")
    void completionResetsConsecutiveFailures() {
      // given
      if (!port.complete(A, r.version()))
        throw new IllegalStateException("given 준비 실패: 완료가 요청을 지우지 않았다");

      // when
      port.requestProjection(A, NOW.plusSeconds(3));

      // then
      assertThat(request(A).consecutiveFailures()).isZero();
    }
  }

  // ---- 요약 ----

  @Test
  @DisplayName("요약은 비어 있으면 0·0·0이다")
  void emptySummary() {
    // when
    OutboxPort.Summary summary = port.summary();

    // then
    assertThat(summary).isEqualTo(new OutboxPort.Summary(0, 0, 0));
  }

  @Test
  @DisplayName("요약은 parked가 아닌 요청 수(backoff 포함)·parked 수·연속 실패 최댓값이다")
  void summaryCountsStatesAndMaxConsecutiveFailures() {
    // given
    port.requestProjection(A, NOW); // 대기
    port.recordFailure(B, givenRequested(B), NOW, "e", POLICY); // backoff
    givenParked(C);

    // when
    OutboxPort.Summary summary = port.summary();

    // then
    assertThat(summary).isEqualTo(new OutboxPort.Summary(2, 1, POLICY.maxAttempts()));
  }
}
