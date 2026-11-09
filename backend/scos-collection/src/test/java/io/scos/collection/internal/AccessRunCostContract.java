package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.AccessCost;
import io.scos.support.LogCapture;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/** 비용 저장·접근 마감·같은 결과 run의 비용 조회를 같은 계약으로 검증한다. */
// 제품 로거의 appender를 공유하므로 같은 JVM의 다른 수집 테스트와 격리한다.
@Isolated
@DisplayName("접근 비용은 run별로 저장하고 같은 결과 run에서 조회한다(T-c10a/b)")
public abstract class AccessRunCostContract {
  private static final AccessCost COST = new AccessCost(2, OptionalLong.of(4), 1250);
  private static final Instant RECORDED_AT = Instant.parse("2026-10-14T00:00:01Z");
  protected AccessRunScenario scenario;

  protected abstract AccessRunScenario newScenario();

  protected abstract AccessRunCostQueryScenario newQueryScenario();

  @Nested
  @DisplayName("접근 run의 비용을 조회할 때")
  class Querying {
    private AccessRunCostQueryScenario query;

    @BeforeEach
    void querySetup() {
      query = newQueryScenario();
    }

    @Test
    @DisplayName("상태 결과 run의 신원과 기록 시각을 비용에 붙인다")
    void sourceStatusReturnsCostOfItsResultRun() {
      // given
      query.givenResultRun(Optional.of(COST));
      // when
      var cost = query.cost();
      // then
      assertThat(cost)
          .contains(
              new SourceQueries.RunCost(
                  new RunId("run-r1"),
                  Instant.parse("2026-10-14T00:00:01Z"),
                  new AccessCost(2, OptionalLong.of(4), 1250)));
    }

    @Test
    @DisplayName("새 실행 중 run은 이전 완료 run의 비용을 빌리지 않는다")
    void runningRunDoesNotBorrowPreviousCost() {
      // given
      query.givenResultRun(Optional.of(COST));
      query.givenNewRunningRun();
      // when
      var cost = query.cost();
      // then
      assertThat(cost).isEmpty();
    }

    @Test
    @DisplayName("측정 전 기존 run의 상태는 비용을 만들지 않는다")
    void oldRunQueryCostIsUnavailable() {
      // given
      query.givenResultRun(Optional.empty());
      // when
      var cost = query.cost();
      // then
      assertThat(cost).isEmpty();
    }

    @Test
    @DisplayName("미측정 호출 수는 실제 0과 구분한다")
    void unknownCallCountIsNotZero() {
      // given
      query.givenResultRun(Optional.of(new AccessCost(2, OptionalLong.empty(), 1250)));
      // when
      var cost = query.cost();
      // then
      assertThat(cost.map(value -> value.cost().callCount())).contains(OptionalLong.empty());
    }

    @Test
    @DisplayName("미측정 완료 run도 이전 완료 run의 비용을 빌리지 않는다")
    void unmeasuredResultDoesNotBorrowPreviousCost() {
      // given
      query.givenResultRun(Optional.of(COST));
      query.givenNewUnmeasuredResultRun();
      // when
      var cost = query.cost();
      // then
      assertThat(cost).isEmpty();
    }

    @Test
    @DisplayName("다른 원천의 더 최신 비용을 빌리지 않는다")
    void sourceDoesNotBorrowOtherSourceCost() {
      // given
      query.givenResultRun(Optional.of(COST));
      query.givenOtherSourceCost(new AccessCost(9, OptionalLong.of(8), 7000));
      // when
      var cost = query.cost();
      // then
      assertThat(cost)
          .contains(
              new SourceQueries.RunCost(
                  new RunId("run-r1"),
                  Instant.parse("2026-10-14T00:00:01Z"),
                  new AccessCost(2, OptionalLong.of(4), 1250)));
    }
  }

  @BeforeEach
  void setUp() {
    scenario = newScenario();
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("측정 전의 기존 run 비용은 미측정이다")
  void oldRunCostIsUnavailable() {
    // then
    assertThat(scenario.seedRun().accessCost()).isEmpty();
  }

  @Nested
  @DisplayName("비용을 기록할 running run을 점유한 상태에서")
  class Running {
    @BeforeEach
    void start() {
      scenario.startCostRun();
    }

    @Test
    @DisplayName("비용을 쓰면 대상과 호출과 시간이 같은 run에서 복원된다")
    void runCostRoundTrips() {
      // when
      scenario.recordCost(COST);
      // then
      assertThat(scenario.costRun().accessCost())
          .contains(
              new CollectionRun.RecordedAccessCost(
                  new AccessCost(2, OptionalLong.of(4), 1250), RECORDED_AT));
    }

    @Test
    @DisplayName("호출 미측정은 0으로 바뀌지 않는다")
    void unknownCallCountRoundTrips() {
      // when
      scenario.recordCost(new AccessCost(0, OptionalLong.empty(), 0));
      // then
      assertThat(scenario.costRun().accessCost())
          .contains(
              new CollectionRun.RecordedAccessCost(
                  new AccessCost(0, OptionalLong.empty(), 0), RECORDED_AT));
    }

    @Test
    @DisplayName("점유 인계 뒤 비용 쓰기는 기존 비용을 덮어쓰지 않는다")
    void lostClaimCannotOverwriteRunCost() {
      scenario.givenRunCost(COST);
      scenario.takeover();
      // when
      scenario.recordCost(new AccessCost(9, OptionalLong.of(9), 9));
      // then
      assertThat(scenario.costRun().accessCost())
          .contains(
              new CollectionRun.RecordedAccessCost(
                  new AccessCost(2, OptionalLong.of(4), 1250), RECORDED_AT));
    }

    @Test
    @DisplayName("비용 쓰기 전에 점유를 잃으면 비용은 미측정이다")
    void lostClaimBeforeCostWriteKeepsCostUnavailable() {
      scenario.takeover();
      // when
      scenario.recordCost(COST);
      // then
      assertThat(scenario.costRun().accessCost()).isEmpty();
    }

    @Test
    @DisplayName("점유 상실은 비용 저장 실패 신호를 남기지 않는다")
    void lostClaimDoesNotReportCostStorageFailure() {
      // given
      scenario.takeover();
      try (var logs = LogCapture.of(AccessCompletion.class)) {
        // when
        scenario.recordCost(COST);
        // then
        assertThat(logs.events("collection.access.cost_failed")).isEmpty();
      }
    }

    @Test
    @DisplayName("마감한 run에는 비용을 기록할 수 없다")
    void terminalRunRejectsCostWrite() {
      scenario.finishCostRun();
      // when·then
      assertThatThrownBy(() -> scenario.storeCost(COST)).isInstanceOf(RunStateException.class);
      assertThat(scenario.costRun().accessCost()).isEmpty();
    }
  }

  @Test
  @DisplayName("접근 중 실패해도 실제 호출 수와 INCOMPLETE를 저장한다")
  void failedAttemptRetainsActualCallCount() {
    scenario.realDrivePermissionFailure();
    // when
    var result = scenario.collect();
    // then
    assertThat(result.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(result.accessCost().map(record -> record.cost().callCount()))
        .contains(OptionalLong.of(3));
  }

  @Test
  @DisplayName("비용 저장 실패는 신호를 남기고 본문을 성공으로 마감한다")
  void costStorageFailureDoesNotPreventTerminalCommit() {
    scenario.costStorageFails();
    try (var logs = LogCapture.of(AccessCompletion.class)) {
      // when
      var result = scenario.collect();
      // then
      assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
      assertThat(result.accessCost()).isEmpty();
      assertThat(logs.events("collection.access.cost_failed")).hasSize(1);
    }
  }

  @Test
  @DisplayName("비용 저장 실패는 앞서 확정한 디렉터리 병합을 보존한다")
  void costFailureDoesNotRollbackDirectoryMerge() {
    scenario.directory("U2", "U3");
    scenario.costStorageFails();
    // when
    scenario.collect();
    // then
    assertThat(scenario.members()).containsExactlyInAnyOrder("U2", "U3");
  }

  @Test
  @DisplayName("비용 저장 실패 신호는 source와 run과 고정 ref만 싣는다")
  void costFailureDoesNotExposeStorageMessage() {
    scenario.costStorageFails();
    try (var logs = LogCapture.of(AccessCompletion.class)) {
      // when
      scenario.collect();
      // then
      assertThat(logs.events("collection.access.cost_failed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(
                          List.of(
                              event.path("level").asString(),
                              event.path("sourceInstanceId").asString(),
                              event.path("runId").asString(),
                              event.path("detail").toString(),
                              event.has("error")))
                      .containsExactly(
                          "warn",
                          "access-source",
                          "access-run",
                          "{\"ref\":\"access_cost_storage\"}",
                          false));
    }
  }

  @Test
  @DisplayName("미시도 접근은 비용을 기록하지 않는다")
  void skippedAccessHasNoCost() {
    scenario.disabled();
    // when
    var result = scenario.collect();
    // then
    assertThat(result.accessCost()).isEmpty();
  }

  @Test
  @DisplayName("조직 전체 지정이 가능한 미지원 원천은 대상 선정 없이 비용을 남기지 않는다")
  void scopedUnsupportedAccessHasNoCost() {
    // given
    scenario.scopedUnsupportedAccess();
    // when
    var result = scenario.collect();
    // then
    assertThat(result.accessResult()).isEqualTo(AccessResult.UNSUPPORTED);
    assertThat(result.accessCost()).isEmpty();
  }

  @Test
  @DisplayName("대상 선정 중 로컬 ACL 저장에 실패하면 비용은 미측정이다")
  void targetSelectionFailureHasNoCost() {
    // given
    scenario.localAclStorageFailsDuringSelection();
    // when
    var result = scenario.collect();
    // then
    assertThat(result.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(result.accessCost()).isEmpty();
  }

  @Test
  @DisplayName("취소된 접근은 실제 0 비용과 구분한다")
  void cancelledAccessHasNoCost() {
    scenario.cancelAfterSuccessfulBody();
    // when
    var result = scenario.collect();
    // then
    assertThat(result.accessCost()).isEmpty();
  }

  @Test
  @DisplayName("공급자가 취소를 보고하면 토큰 상태와 관계없이 비용은 미측정이다")
  void providerCancellationHasNoCost() {
    scenario.cancellationDuringAccess();
    // when
    var result = scenario.collect();
    // then
    assertThat(result.accessCost()).isEmpty();
  }

  @Test
  @DisplayName("시계가 역행해도 음수 시간 없이 run을 성공으로 마감한다")
  void backwardClockDoesNotPreventTerminalCommit() {
    scenario.clockMovesBackDuringAccess();
    // when
    var result = scenario.collect();
    // then
    assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(result.accessCost().map(CollectionRun.RecordedAccessCost::cost))
        .contains(new AccessCost(1, OptionalLong.empty(), 0));
  }

  @Test
  @DisplayName("정상 선정한 빈 대상은 실제 대상 0으로 저장한다")
  void emptySelectionRecordsActualZero() {
    scenario.emptyCostTargets();
    // when
    var result = scenario.collect();
    // then
    assertThat(result.accessCost().map(CollectionRun.RecordedAccessCost::cost))
        .contains(new AccessCost(0, OptionalLong.empty(), 1000));
  }

  @Test
  @DisplayName("본문 마감 실패 후에도 비용은 해당 실패 run에 남는다")
  void terminalFailureRetainsCostOnItsRun() {
    scenario.completionStorageFails(1);
    // when
    var result = scenario.collect();
    // then
    assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    assertThat(result.accessCost().map(CollectionRun.RecordedAccessCost::cost))
        .contains(new AccessCost(1, OptionalLong.empty(), 1000));
  }

  @Test
  @DisplayName("실제 접근 시도의 대상 수와 전체 경과 시간을 저장한다")
  void collectionMeasuresTargetsAndElapsedTime() {
    // when
    var result = scenario.collect();
    // then
    assertThat(result.accessCost().map(CollectionRun.RecordedAccessCost::cost))
        .contains(new AccessCost(1, OptionalLong.empty(), 1000));
  }
}
