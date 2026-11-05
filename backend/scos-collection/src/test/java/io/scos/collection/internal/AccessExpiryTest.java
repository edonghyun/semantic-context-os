package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.support.LogCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

// 제품 로거는 JVM에 하나라 만료 실패 캡처와 다른 테스트의 로그를 격리한다.
@Isolated
@DisplayName("관측 만료는 비활성 소스에서도 원본·문서·디렉터리를 제한한다")
class AccessExpiryTest {
  private static final Duration BEFORE_MINUTE = Duration.ofSeconds(59);
  private static final Duration MINUTE = Duration.ofMinutes(1);
  private static final Duration SECOND = Duration.ofSeconds(1);
  private final AccessExpiryScenario scenario = new AccessExpiryScenario();

  @Test
  @DisplayName("잠금을 기다리는 사이 닫힌 문서는 만료 집계에 더하지 않는다")
  void closedWhileClaimingIsNotCounted() {
    scenario.givenExpiredDrive();
    scenario.closeDocumentWhileWaitingForClaim();
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      scenario.sweep();
      // then
      assertThat(logs.events("collection.access.expired"))
          .singleElement()
          .satisfies(
              event -> assertThat(event.path("detail").path("documentsClosed").asInt()).isZero());
    }
  }

  @Test
  @DisplayName("잠금을 기다리는 사이 삭제된 문서는 만료 집계에 더하지 않는다")
  void removedWhileClaimingIsNotCounted() {
    scenario.givenExpiredDrive();
    scenario.removeDocumentWhileWaitingForClaim();
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      scenario.sweep();
      // then
      assertThat(logs.events("collection.access.expired"))
          .singleElement()
          .satisfies(
              event -> assertThat(event.path("detail").path("documentsClosed").asInt()).isZero());
    }
  }

  @Test
  @DisplayName("디렉터리 트랜잭션 시작 때 취소되면 디렉터리도 읽지 않는다")
  void cancelledDirectoryTransactionReadsNothing() {
    // given
    scenario.givenExpiredDrive();
    scenario.cancelWhenDirectoryTransactionBegins();
    // when
    scenario.sweep();
    // then
    assertThat(scenario.directoryReads()).isZero();
  }

  @Test
  @DisplayName("잠긴 디렉터리 읽기 중 취소되면 그룹을 바꾸지 않는다")
  void cancellationDuringLockedDirectoryReadPreservesGroup() {
    // given
    scenario.givenExpiredSlackGroup();
    scenario.cancelDuringLockedDirectoryRead();
    // when
    scenario.sweep();
    // then
    assertThat(scenario.group().members()).containsExactly("U1");
  }

  @Test
  @DisplayName("후보 읽기 중 취소되면 다음 후보를 읽지 않는다")
  void cancellationDuringCandidateReadStopsFurtherReads() {
    // given
    scenario.givenExpiredDrive();
    scenario.cancelDuringRawRead();
    // when
    scenario.sweep();
    // then
    assertThat(scenario.rawRowsRead()).isEqualTo(1);
  }

  @Test
  @DisplayName("소스 잠금 전에 신선해진 그룹은 만료 로그를 만들지 않는다")
  void freshDirectoryBeforeSourceLockLogsNoExpiry() {
    // given
    scenario.givenExpiredSlackGroup();
    scenario.refreshGroupAfterDirectoryProbe();
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      // when
      scenario.sweep();
      // then
      assertThat(logs.events("collection.access.directory_expired")).isEmpty();
    }
  }

  @Test
  @DisplayName("이미 FAILED인 문서는 원본을 만료해도 소유를 잠그지 않는다")
  void failedDocumentIsNotClaimedDuringRawExpiry() {
    // given
    scenario.givenDriveWithUnchangedDocument("failed");
    // when
    scenario.sweep();
    // then
    assertThat(scenario.ownershipClaims()).isZero();
  }

  @Test
  @DisplayName("지연 OK 문서를 닫을 때 이미 EXPIRED인 원본은 다시 관측하지 않는다")
  void expiredRawIsNotObservedWhileClosingLateDocument() {
    // given
    scenario.givenExpiredRawWithVisibleDocument();
    // when
    scenario.sweep();
    // then
    assertThat(scenario.rawObservations()).isZero();
  }

  @Test
  @DisplayName("후보 조회 뒤 설정이 바뀌면 다음 후보 조회를 하지 않는다")
  void changedSourceAfterCandidateReadStopsFurtherReads() {
    // given
    scenario.givenExpiredDrive();
    scenario.changeSourceAfterPage();
    // when
    scenario.sweep();
    // then
    assertThat(scenario.rawRowsRead()).isEqualTo(1);
  }

  @Test
  @DisplayName("디렉터리 선조회 뒤 설정이 바뀌면 그룹을 바꾸지 않는다")
  void changedSourceAfterDirectoryReadPreservesGroup() {
    // given
    scenario.givenExpiredSlackGroup();
    scenario.changeSourceAfterDirectoryRead();
    // when
    scenario.sweep();
    // then
    assertThat(scenario.group().members()).containsExactly("U1");
  }

  @Test
  @DisplayName("후보 조회 뒤 도착한 신선한 관측의 문서는 닫지 않는다")
  void freshObservationWhileAcquiringSourceIsPreserved() {
    // given
    scenario.givenExpiredDrive();
    scenario.observeFreshAfterCandidateRead();
    // when
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("새 관측 없는 두 번째 sweep은 원본 후보를 반환하지 않는다")
  void repeatedSweepReadsNoClosedCandidates() {
    // given
    scenario.givenExpiredDrive();
    scenario.sweep();
    scenario.resetWorkCounts();
    scenario.advance(MINUTE);
    // when
    scenario.sweep();
    // then
    assertThat(scenario.rawRowsRead()).isZero();
  }

  @Test
  @DisplayName("다른 소유자의 OK 문서가 있어도 두 번째 sweep 후보는 0행이다")
  void repeatedForeignOwnerSweepHasNoCandidates() {
    scenario.givenDriveWithUnchangedDocument("foreign-source");
    scenario.sweep();
    scenario.resetWorkCounts();
    scenario.advance(MINUTE);
    scenario.sweep();
    // then
    assertThat(scenario.rawRowsRead()).isZero();
  }

  @Test
  @DisplayName("새 관측 없는 두 번째 sweep은 닫힌 원본을 잠그는 observe를 부르지 않는다")
  void repeatedSweepDoesNotObserveClosedRaw() {
    scenario.givenExpiredDrive();
    scenario.sweep();
    scenario.resetWorkCounts();
    scenario.advance(MINUTE);
    scenario.sweep();
    // then
    assertThat(scenario.rawObservations()).isZero();
  }

  @Test
  @DisplayName("새 관측 없는 두 번째 sweep은 닫힌 문서를 claim하지 않는다")
  void repeatedSweepDoesNotClaimClosedDocument() {
    scenario.givenExpiredDrive();
    scenario.sweep();
    scenario.resetWorkCounts();
    scenario.advance(MINUTE);
    scenario.sweep();
    // then
    assertThat(scenario.ownershipClaims()).isZero();
  }

  @Test
  @DisplayName("빈 후보 조회 중 취소된 sweep은 새 시도의 즉시 재시도를 막지 않는다")
  void cancelledEmptyPageDoesNotThrottleRetry() {
    scenario.givenEmptyDriveSource();
    scenario.cancelDuringRawRead();
    scenario.sweep();
    scenario.beginNewSweepAttempt();
    scenario.givenExpiredDrive();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("원본 트랜잭션 시작 때 취소되면 만료 후보도 읽지 않는다")
  void cancellationBeforeRawTransactionReadsNoCandidates() {
    scenario.givenExpiredDrive();
    scenario.cancelWhenRawTransactionBegins();
    scenario.sweep();
    // then
    assertThat(scenario.rawRowsRead()).isZero();
  }

  @Test
  @DisplayName("첫 원본 뒤 소스 설정이 바뀌면 다음 문서는 닫지 않는다")
  void changedSourceBetweenClosuresStopsRemainingDocument() {
    scenario.givenExpiredDrive();
    scenario.givenSecondExpiredDriveDocument();
    scenario.changeSourceAfterFirstRawClosure();
    scenario.sweep();
    // then
    assertThat(scenario.document("file:F2").status()).isEqualTo(Status.OK);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unprojected", "foreign-source", "failed", "foreign-space"})
  @DisplayName("실제로 바꾸지 않은 문서 ACL은 만료 집계에 더하지 않는다")
  void unchangedDocumentsAreNotCountedAsClosed(String state) {
    scenario.givenDriveWithUnchangedDocument(state);
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      scenario.sweep();
      // then
      assertThat(logs.events("collection.access.expired"))
          .singleElement()
          .satisfies(
              event -> assertThat(event.path("detail").path("documentsClosed").asInt()).isZero());
    }
  }

  @Test
  @DisplayName("이미 만료된 원본에 남은 OK 문서는 문서만 닫은 수로 보고한다")
  void expiredRawWithVisibleDocumentReportsOnlyDocumentClosure() {
    scenario.givenExpiredRawWithVisibleDocument();
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      scenario.sweep();
      // then
      assertThat(logs.events("collection.access.expired"))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.path("detail").path("rawClosed").asInt()).isZero();
                assertThat(event.path("detail").path("documentsClosed").asInt()).isEqualTo(1);
              });
    }
  }

  @Test
  @DisplayName("신선한 원본은 만료 로그를 만들지 않는다")
  void freshRawLogsNoClosures() {
    scenario.givenFreshDrive();
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      scenario.sweep();
      // then
      assertThat(logs.events("collection.access.expired")).isEmpty();
    }
  }

  @Test
  @DisplayName("디렉터리 만료도 실제 그룹과 신원 수를 보고한다")
  void expiredDirectoryReportsClosureCounts() {
    scenario.givenExpiredSlackGroup();
    scenario.givenExpiredIdentity();
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      scenario.sweep();
      // then
      assertThat(logs.events("collection.access.directory_expired"))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.path("detail").path("groupsClosed").asInt()).isEqualTo(1);
                assertThat(event.path("detail").path("identitiesClosed").asInt()).isEqualTo(1);
              });
    }
  }

  @Test
  @DisplayName("바뀐 디렉터리가 없으면 만료 로그도 없다")
  void unchangedDirectoryLogsNoClosures() {
    scenario.givenEmptyDriveSource();
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      scenario.sweep();
      // then
      assertThat(logs.events("collection.access.directory_expired")).isEmpty();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Status.class,
      names = {"FAILED", "UNKNOWN"})
  @DisplayName("원본이 이미 닫혀도 남은 OK 문서는 상한에 닫는다")
  void expiredNonOkRawStillClosesVisibleDocument(Status status) {
    scenario.givenExpiredDriveWithRawStatus(status);
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("상한 만료는 닫은 원본·문서 수와 원인을 기록한다")
  void expirationLogsClosureCause() {
    scenario.givenExpiredDrive();
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      scenario.sweep();
      // then
      assertThat(logs.events("collection.access.expired"))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.path("detail").path("rawClosed").asInt()).isEqualTo(1);
                assertThat(event.path("detail").path("documentsClosed").asInt()).isEqualTo(1);
              });
    }
  }

  @Test
  @DisplayName("재관측 없는 Slack 소스는 sweep에서 원본을 읽지 않는다")
  void slackSweepReadsNoRawRows() {
    scenario.givenSlackCarrier();
    scenario.sweep();
    // then
    assertThat(scenario.rawRowsRead()).isZero();
  }

  @Test
  @DisplayName("상한 안의 Drive 원본은 만료 조회에서 읽지 않는다")
  void freshSweepReadsNoRawRows() {
    scenario.givenFreshDrive();
    scenario.sweep();
    // then
    assertThat(scenario.rawRowsRead()).isZero();
  }

  @Test
  @DisplayName("만료 대상의 본문도 sweep에서 읽지 않는다")
  void expiredSweepReadsNoBody() {
    scenario.givenExpiredDrive();
    scenario.sweep();
    // then
    assertThat(scenario.rawBodiesRead()).isZero();
  }

  @Test
  @DisplayName("비활성 소스의 오래된 문서는 수집이나 투영 없이 FAILED다")
  void disabledSourceDocumentExpiresWithoutCollection() {
    scenario.givenExpiredDrive();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.FAILED);
    assertThat(scenario.raw().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("만료는 원천 관측 시각을 보존한다")
  void expirationPreservesSourceObservation() {
    scenario.givenExpiredDrive();
    scenario.sweep();
    // then
    assertThat(scenario.raw().observedAt()).isEqualTo(AccessExpiryScenario.T0);
  }

  @Test
  @DisplayName("상한 직전 원본은 허용을 유지한다")
  void freshDocumentIsPreserved() {
    scenario.givenFreshDrive();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("비활성 Slack 소스의 오래된 그룹은 구성원을 잃는다")
  void disabledSlackDirectoryExpires() {
    scenario.givenExpiredSlackGroup();
    scenario.sweep();
    // then
    assertThat(scenario.group().members()).isEmpty();
  }

  @Test
  @DisplayName("취소된 만료 처리는 저장을 바꾸지 않는다")
  void cancelledSweepWritesNothing() {
    scenario.givenExpiredDrive();
    scenario.cancel();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("수집이 꺼져 있으면 만료 쓰기도 하지 않는다")
  void disabledCollectionWritesNothing() {
    scenario.givenExpiredDrive();
    scenario.disableCollection();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("디렉터리 읽기 중 취소되면 그룹을 바꾸지 않는다")
  void cancellationDuringDirectoryReadWritesNothing() {
    scenario.givenExpiredSlackGroup();
    scenario.cancelDuringDirectoryRead();
    scenario.sweep();
    // then
    assertThat(scenario.group().members()).containsExactly("U1");
  }

  @Test
  @DisplayName("오래된 신원은 시각과 메타데이터를 보존하며 제한한다")
  void expiredIdentityIsRestrictedWithoutInventedObservation() {
    scenario.givenExpiredIdentity();
    scenario.sweep();
    // then
    assertThat(scenario.identity())
        .isEqualTo(
            new SourceIdentityRecord(
                "U1",
                Optional.of("alice@example.com"),
                true,
                false,
                true,
                AccessExpiryScenario.T0));
  }

  @Test
  @DisplayName("1분 직전 틱은 같은 원천을 다시 훑지 않는다")
  void sweepBeforeIntervalDoesNotRescan() {
    scenario.givenFreshDrive();
    scenario.sweep();
    scenario.advance(BEFORE_MINUTE);
    scenario.sweep();
    // then
    assertThat(scenario.directoryReads()).isEqualTo(1);
  }

  @Test
  @DisplayName("정확히 1분 뒤 틱은 원천을 다시 훑는다")
  void sweepAtIntervalRescans() {
    scenario.givenFreshDrive();
    scenario.sweep();
    scenario.advance(MINUTE);
    scenario.sweep();
    // then
    assertThat(scenario.directoryReads()).isEqualTo(2);
  }

  @Test
  @DisplayName("상한이 1분보다 짧으면 그 간격에 다시 훑는다")
  void shortMaxAgeControlsSweepInterval() {
    scenario.givenFreshDrive();
    scenario.maxAge(SECOND);
    scenario.sweep();
    scenario.advance(SECOND);
    scenario.sweep();
    // then
    assertThat(scenario.directoryReads()).isEqualTo(2);
  }

  @Test
  @DisplayName("한 원천 만료 실패는 뒤 원천의 제한을 막지 않는다")
  void failureIsSourceScopedAndLogged() {
    scenario.givenExpiredDrive();
    scenario.givenExpiredSlackGroup();
    scenario.failDriveDirectory();
    try (var logs = LogCapture.of(AccessExpiry.class)) {
      scenario.sweep();
      // then
      assertThat(scenario.group().members()).isEmpty();
      assertThat(logs.events("collection.access.expiry_failed"))
          .singleElement()
          .satisfies(
              event -> assertThat(event.path("sourceInstanceId").asString()).isEqualTo("drive-a"));
    }
  }

  @Test
  @DisplayName("실패한 훑기는 간격을 기다리지 않고 다음 틱에 재시도한다")
  void failedSweepRetriesImmediately() {
    scenario.givenExpiredDrive();
    scenario.failDriveDirectory();
    scenario.sweep();
    scenario.recoverDirectory();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("미투영 원본 만료는 문서 소유를 새로 만들지 않는다")
  void unprojectedRawDoesNotClaimDocument() {
    scenario.givenUnprojectedDrive();
    scenario.sweep();
    // then
    assertThat(scenario.ownedDocuments()).isEmpty();
  }

  @Test
  @DisplayName("현재 공간 매핑이 달라도 원래 소유 공간의 문서를 닫는다")
  void expiresDocumentInItsOwnedSpace() {
    scenario.givenExpiredDrive();
    scenario.changeConfiguredSpace();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("다른 출처 문서는 보존하며 자신의 오래된 원본만 제한한다")
  void expiryDoesNotOverwriteForeignOwnership() {
    scenario.givenExpiredDriveWithForeignOwner();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.OK);
    assertThat(scenario.raw().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("이전 문서 결정 시각이 미래여도 제한 결정은 더 늦게 저장한다")
  void expiryAdvancesPastFutureDocumentDecision() {
    scenario.givenExpiredDriveWithFutureDecision();
    scenario.sweep();
    // then
    assertThat(scenario.document())
        .extracting(DocumentAcl::status, DocumentAcl::observedAt)
        .containsExactly(Status.FAILED, Instant.parse("2026-10-15T00:00:01.000001Z"));
  }

  @Test
  @DisplayName("만료된 원본의 제한은 투영을 요청한다")
  void rawRestrictionRequestsProjection() {
    scenario.givenExpiredDrive();
    scenario.sweep();
    // then
    assertThat(scenario.projectionRequested()).isTrue();
  }

  @Test
  @DisplayName("신선한 원본은 투영을 요청하지 않는다")
  void freshRawDoesNotRequestProjection() {
    scenario.givenFreshDrive();
    scenario.sweep();
    // then
    assertThat(scenario.projectionRequested()).isFalse();
  }

  @Test
  @DisplayName("원본 재조회 중 취소되면 제한을 쓰지 않는다")
  void cancellationDuringRawReadWritesNothing() {
    scenario.givenExpiredDrive();
    scenario.cancelDuringRawRead();
    scenario.sweep();
    // then
    assertThat(scenario.raw().status()).isEqualTo(Status.OK);
    assertThat(scenario.document().status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("원본 재조회 중 설정이 달라지면 쓰지 않는다")
  void changedSourceDuringRawReadWritesNothing() {
    scenario.givenExpiredDrive();
    scenario.changeSourceDuringRawRead();
    scenario.sweep();
    // then
    assertThat(scenario.raw().status()).isEqualTo(Status.OK);
    assertThat(scenario.document().status()).isEqualTo(Status.OK);
  }

  @Test
  @DisplayName("소유 행 없는 기존 ACL은 설정 공간에서 만료한다")
  void ownerlessExistingAclExpiresInConfiguredSpace() {
    scenario.givenOwnerlessDocument();
    scenario.sweep();
    // then
    assertThat(scenario.document().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("캡처 뒤 설정이 달라지면 디렉터리를 읽지 않는다")
  void changedCapturedSourceDoesNotReadDirectory() {
    scenario.givenExpiredDrive();
    scenario.changeSourceAfterList();
    scenario.sweep();
    // then
    assertThat(scenario.directoryReads()).isZero();
  }

  @Test
  @DisplayName("배치 조회 뒤 설정이 달라지면 원본을 재조회하지 않는다")
  void changedSourceAfterPageDoesNotReadRawRecords() {
    scenario.givenExpiredDrive();
    scenario.changeSourceAfterPage();
    scenario.sweep();
    // then
    assertThat(scenario.rawReads()).isZero();
  }
}
