package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.googledrive.internal.GoogleDriveViews.RequestView;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재시도 경과 예산(ProviderCallPolicy 10분): 대기만큼 시계가 흐르면 변경 피드의 403 rateLimitExceeded(Retry-After 300)는 시도
 * 수 상한(5)에 닿기 전에 예산으로 끝난다 — 첫 실패 뒤 5분을 기다리고, 둘째 실패에서는 경과 5분 + 대기 5분이 예산에 닿아 기다리지 않는다. 운영
 * 해석("Retry-After가 계속되면 약 5분 뒤 rate_limited")의 근거다. TS 동등 각본(expected-failures.json)은 시계가 멈춘 채 시도 수
 * 상한까지 간다.
 */
@DisplayName("Drive 변경 피드 rate limit이 계속되면 재시도 경과 예산(10분)으로 끝난다")
class GoogleDriveRetryBudgetTest {
  static final Duration RETRY_AFTER = Duration.ofMinutes(5);
  static final String CHANGES = "/drive/v3/changes";

  final GoogleDriveScenario drive = new GoogleDriveScenario();

  @BeforeEach
  void givenRateLimitedChangesWithFlowingClock() {
    drive.givenOverrides(
        GoogleDriveCollectionContract.failureRun("rateLimitExceeded").get("overrides"));
    drive.clockFlowsWhileWaiting();
  }

  @AfterEach
  void tearDown() {
    drive.close();
  }

  @Test
  @DisplayName("Retry-After만큼 한 번만 기다린다")
  void waitsOnceForRetryAfter() {
    // when
    drive.collect();

    // then
    assertThat(drive.waits()).containsExactly(RETRY_AFTER);
  }

  @Test
  @DisplayName("변경 피드를 두 번 시도하고 멈춘다")
  void triesChangesTwice() {
    // when
    drive.collect();

    // then
    assertThat(drive.requests().stream().map(RequestView::path))
        .filteredOn(CHANGES::equals)
        .hasSize(2);
  }

  @Test
  @DisplayName("rate_limited로 끝난다")
  void endsRateLimited() {
    // when
    GoogleDriveScenario.Collection java = drive.collect();

    // then
    assertThat(java.failureKind()).contains("rate_limited");
  }
}
