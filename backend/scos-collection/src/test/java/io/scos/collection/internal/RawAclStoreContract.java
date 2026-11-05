package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 원본 ACL은 의미 변경과 관측 고수위를 분리하고 동일 시각 충돌에서 권한을 좁힌다. */
@DisplayName("원본 ACL 저장은 소스 격리·단조 관측·상충 제한을 지킨다")
public abstract class RawAclStoreContract {
  static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  static final SourceInstanceId OTHER = new SourceInstanceId("drive-b");
  static final String ID = "file-1";
  static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final Instant T1 = T0.plusSeconds(1);
  static final Instant T2 = T0.plusSeconds(2);
  static final AccessKey ALICE = AccessKey.parse("user:drive-a:a@example.com");
  static final AccessKey BOB = AccessKey.parse("user:drive-a:b@example.com");
  RawAclScenario scenario;

  protected abstract RawAclScenario newScenario();

  @BeforeEach
  void setUp() {
    scenario = newScenario();
  }

  @Test
  @DisplayName("본문 없는 접근 후보도 원천 시각과 순서를 보존한다")
  void accessCandidatesPreserveSourceMetadata() {
    scenario.upsert(
        SOURCE,
        RawItems.item(ID)
            .resourceType("drive.file")
            .at("0001")
            .sourceCreatedAt(Optional.of("2026-10-01T00:00:00Z"))
            .sourceUpdatedAt(Optional.of("2026-10-02T00:00:00Z"))
            .upsert());
    var item =
        scenario
            .accessCandidates(SOURCE, Set.of("drive.file"), Optional.empty(), 10, Optional.empty())
            .items()
            .getFirst();
    // then
    assertThat(item)
        .extracting(
            raw -> raw.sourceCreatedAt(), raw -> raw.sourceUpdatedAt(), raw -> raw.sourceOrder())
        .containsExactly(
            Optional.of("2026-10-01T00:00:00Z"),
            Optional.of("2026-10-02T00:00:00Z"),
            Optional.of(new SourceOrder("slack.message-state.v1", "0001")));
  }

  @Test
  @DisplayName("같은 FAILED라도 새로운 관측의 원인으로 갱신한다")
  void newerFailureReasonReplacesPriorReason() {
    scenario.givenItem(SOURCE, ID);
    scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.MISSING_TARGET, T0), T0);
    scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, T1), T1);
    // then
    assertThat(scenario.current(SOURCE, ID).reason()).isEqualTo(AccessFailureReason.ACL_UNREADABLE);
  }

  @Test
  @DisplayName("FAILED 원본의 원인을 저장하고 원본 조회에서도 보존한다")
  void failureReasonSurvivesRawRoundTrip() {
    scenario.givenItem(SOURCE, ID);
    scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, T0), T0);
    // then
    assertThat(scenario.current(SOURCE, ID).reason()).isEqualTo(AccessFailureReason.ACL_UNREADABLE);
    assertThat(((RawAcl.Observed) scenario.item(SOURCE, ID).acl()).reason())
        .isEqualTo(AccessFailureReason.ACL_UNREADABLE);
  }

  @Test
  @DisplayName("만료 후보는 경계 시각 이하의 관측(상태 무관)을 반환한다")
  void expiredCandidatesFilterObservedCutoff() {
    scenario.givenItem(SOURCE, "expired");
    scenario.givenItem(SOURCE, "fresh");
    scenario.givenItem(SOURCE, "failed");
    scenario.givenItem(SOURCE, "unknown");
    scenario.givenItem(SOURCE, "unobserved");
    scenario.observe(SOURCE, "expired", RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    scenario.observe(SOURCE, "fresh", RawAcl.observed(Status.OK, Set.of(ALICE), T1), T1);
    scenario.observe(SOURCE, "failed", RawAcl.observed(Status.FAILED, Set.of(), T0), T0);
    scenario.observe(SOURCE, "unknown", RawAcl.observed(Status.UNKNOWN, Set.of(), T0), T0);
    var page =
        scenario.accessCandidates(
            SOURCE, Set.of("drive.file"), Optional.of(T0), 10, Optional.empty());
    // then
    assertThat(page.items())
        .extracting(item -> item.externalId())
        .containsExactly("expired", "failed", "unknown");
  }

  @Test
  @DisplayName("접근 후보는 원본 본문을 반환하지 않는다")
  void accessCandidatesContainNoBody() {
    scenario.givenItem(SOURCE, ID);
    var page =
        scenario.accessCandidates(
            SOURCE, Set.of("drive.file"), Optional.empty(), 10, Optional.empty());
    // then
    assertThat(page.items())
        .singleElement()
        .satisfies(item -> assertThat(item.content()).isEmpty());
  }

  @Test
  @DisplayName("접근 후보의 종류가 비면 원본을 반환하지 않는다")
  void emptyKindsSelectNoCandidates() {
    scenario.givenItem(SOURCE, ID);
    var page = scenario.accessCandidates(SOURCE, Set.of(), Optional.empty(), 10, Optional.empty());
    // then
    assertThat(page.items()).isEmpty();
  }

  @Test
  @DisplayName("접근 후보는 소스와 종류를 격리한다")
  void accessCandidatesAreSourceAndKindScoped() {
    scenario.givenItem(SOURCE, ID);
    scenario.givenItem(OTHER, "other");
    scenario.upsert(SOURCE, RawItems.item("slack").upsert());
    var page =
        scenario.accessCandidates(
            SOURCE, Set.of("drive.file"), Optional.empty(), 10, Optional.empty());
    // then
    assertThat(page.items()).extracting(item -> item.externalId()).containsExactly(ID);
  }

  @Test
  @DisplayName("접근 후보 커서는 코드 유닛 순으로 다음 일치 항목부터 읽는다")
  void accessCandidateCursorSkipsPreviousPage() {
    scenario.givenItem(SOURCE, "A");
    scenario.givenItem(SOURCE, "B");
    scenario.givenItem(SOURCE, "C");
    var page =
        scenario.accessCandidates(
            SOURCE, Set.of("drive.file"), Optional.empty(), 1, Optional.of("A"));
    // then
    assertThat(page.items()).extracting(item -> item.externalId()).containsExactly("B");
    assertThat(page.nextCursor()).contains("B");
  }

  @Test
  @DisplayName("최초 비관측은 fallback 시각의 빈 UNKNOWN을 만든다")
  void absentNotObservedCreatesUnknown() {
    scenario.observe(SOURCE, ID, RawAcl.notObserved(), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.UNKNOWN, Set.of(), T0));
  }

  @Test
  @DisplayName("비관측은 기존 OK 권한과 시각을 유지한다")
  void notObservedPreservesOk() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    scenario.observe(SOURCE, ID, RawAcl.notObserved(), T2);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.OK, Set.of(ALICE), T0));
  }

  @Test
  @DisplayName("오래된 관측은 새 FAILED를 되돌리지 못한다")
  void olderObservationCannotRestoreGrant() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T2), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T1), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.FAILED, Set.of(), T2));
  }

  @Test
  @DisplayName("같은 시각의 OK 상충은 키 교집합으로 좁힌다")
  void equalTimeConflictingAclRestricts() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE, BOB), T0), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(BOB), T0), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.OK, Set.of(BOB), T0));
  }

  @Test
  @DisplayName("같은 시각의 넓은 OK가 다시 와도 좁은 권한을 유지한다")
  void equalTimeConflictCannotExpand() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(BOB), T0), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE, BOB), T0), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.OK, Set.of(BOB), T0));
  }

  @Test
  @DisplayName("같은 시각의 상태 상충은 빈 FAILED다")
  void equalTimeDifferentStatusFailsClosed() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.UNKNOWN, Set.of(), T0), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(RawAcl.failed(AccessFailureReason.CONFLICTING_OBSERVATIONS, T0));
  }

  @Test
  @DisplayName("같은 externalId여도 다른 소스에는 관측이 없다")
  void sourceIsolation() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    // then
    assertThat(scenario.observations(OTHER, List.of(ID))).isEmpty();
  }

  @Test
  @DisplayName("같은 지문의 최신 관측 뒤 지연 응답을 거부한다")
  void sameFingerprintAdvancesObservationFence() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T2), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T1), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.OK, Set.of(ALICE), T2));
  }

  @Test
  @DisplayName("같은 지문이어도 최신 원천 관측 시각을 저장한다")
  void sameFingerprintStoresLatestObservedAt() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T2), T0);
    // then
    assertThat(scenario.current(SOURCE, ID).observedAt()).isEqualTo(T2);
  }

  @Test
  @DisplayName("같은 지문은 의미 변경이 아니다")
  void sameFingerprintReportsNoSemanticChange() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    boolean changed =
        scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T2), T0);
    // then
    assertThat(changed).isFalse();
  }

  @Test
  @DisplayName("첫 관측은 의미 변경이다")
  void newObservationReportsChange() {
    boolean changed =
        scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    // then
    assertThat(changed).isTrue();
  }

  @Test
  @DisplayName("더 최신 시각의 OK는 FAILED를 대체한다")
  void newerGrantReplacesRestriction() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T0), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T1), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.OK, Set.of(ALICE), T1));
  }

  @Test
  @DisplayName("더 최신의 다른 의미는 변경을 보고한다")
  void changedObservationReportsChange() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    boolean changed =
        scenario.observe(SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T1), T0);
    // then
    assertThat(changed).isTrue();
  }

  @Test
  @DisplayName("같은 시각 같은 관측은 변경이 아니다")
  void equalObservationReportsNoChange() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    boolean changed =
        scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    // then
    assertThat(changed).isFalse();
  }

  @Test
  @DisplayName("다른 원천 키를 가진 관측은 빈 FAILED로 저장한다")
  void sourceMismatchFailsClosed() {
    scenario.observe(
        SOURCE,
        ID,
        RawAcl.observed(Status.OK, Set.of(AccessKey.parse("user:drive-b:a@example.com")), T0),
        T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(RawAcl.failed(AccessFailureReason.INVALID_CARRIER, T0));
  }

  @Test
  @DisplayName("비정규 원천 인스턴스는 쓰기를 거부한다")
  void noncanonicalSourceIsRejected() {
    // then
    assertThatThrownBy(
            () -> scenario.observe(new SourceInstanceId("drive:a"), ID, RawAcl.notObserved(), T0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("FAILED에 따라온 키는 저장하지 않는다")
  void failedHasNoKeys() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(ALICE), T0), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.FAILED, Set.of(), T0));
  }

  @Test
  @DisplayName("UNKNOWN에 따라온 키는 저장하지 않는다")
  void unknownHasNoKeys() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.UNKNOWN, Set.of(ALICE), T0), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.UNKNOWN, Set.of(), T0));
  }

  @Test
  @DisplayName("빈 키 집합 조회는 비어 있다")
  void emptyGetIsEmpty() {
    // then
    assertThat(scenario.observations(SOURCE, List.of())).isEmpty();
  }

  @Test
  @DisplayName("명시 삭제는 지정 소스와 키만 지운다")
  void deleteIsSourceScoped() {
    scenario.observe(SOURCE, ID, RawAcl.notObserved(), T0);
    scenario.observe(SOURCE, "kept", RawAcl.notObserved(), T0);
    scenario.observe(OTHER, ID, RawAcl.notObserved(), T0);
    scenario.forget(SOURCE, List.of(ID));
    // then
    assertThat(scenario.observations(SOURCE, List.of(ID, "kept"))).containsOnlyKeys("kept");
    assertThat(scenario.observations(OTHER, List.of(ID))).containsOnlyKeys(ID);
  }

  @Test
  @DisplayName("빈 삭제 집합은 기존 관측을 유지한다")
  void emptyDeletePreservesRows() {
    scenario.observe(SOURCE, ID, RawAcl.notObserved(), T0);
    scenario.forget(SOURCE, List.of());
    // then
    assertThat(scenario.observations(SOURCE, List.of(ID))).containsOnlyKeys(ID);
  }

  @Test
  @DisplayName("본문 저장 뒤 ACL 관측은 원본 get에 반영된다")
  void getReadsIndependentAcl() {
    scenario.givenItem(SOURCE, ID);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T1), T0);
    // then
    assertThat(scenario.item(SOURCE, ID).acl())
        .isEqualTo(new RawAcl.Observed(Status.OK, Set.of(ALICE), T1));
  }

  @Test
  @DisplayName("활성 원본 목록은 최신 ACL을 읽는다")
  void listReadsIndependentAcl() {
    scenario.givenItem(SOURCE, ID);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T1), T0);
    var page = scenario.activePage(SOURCE);
    // then
    assertThat(page.items())
        .extracting(item -> item.acl())
        .containsExactly(new RawAcl.Observed(Status.OK, Set.of(ALICE), T1));
  }

  @Test
  @DisplayName("ACL 행이 없는 원본은 비관측이다")
  void absentAclReadsNotObserved() {
    scenario.givenItem(SOURCE, ID);
    // then
    assertThat(scenario.item(SOURCE, ID).acl()).isEqualTo(new RawAcl.NotObserved());
  }

  @Test
  @DisplayName("본문 upsert는 ACL 저장을 대신하지 않는다")
  void upsertAloneDoesNotObserveAcl() {
    scenario.upsert(
        SOURCE,
        RawItems.item(ID)
            .resourceType("drive.file")
            .acl(RawAcl.observed(Status.OK, Set.of(ALICE), T0))
            .upsert());
    // then
    assertThat(scenario.item(SOURCE, ID).acl()).isEqualTo(new RawAcl.NotObserved());
  }

  @Test
  @DisplayName("독립 ACL 저장은 원본 본문과 관측 시각을 보존한다")
  void aclObservationPreservesBody() {
    scenario.givenItem(SOURCE, ID);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T2), T0);
    // then
    assertThat(scenario.item(SOURCE, ID).metadataJson())
        .isEqualTo(
            "{\"id\":\"file-1\",\"name\":\"Plan\",\"mimeType\":\"application/vnd.google-apps.document\"}");
    assertThat(scenario.item(SOURCE, ID).observedAt()).isEqualTo(T0);
  }

  @Test
  @DisplayName("같은 externalId의 다른 소스에 ACL을 결합하지 않는다")
  void aclJoinIsSourceScoped() {
    scenario.givenItem(OTHER, ID);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    // then
    assertThat(scenario.item(OTHER, ID).acl()).isEqualTo(new RawAcl.NotObserved());
  }

  @Test
  @DisplayName("원본 없는 ACL은 활성 원본 목록에 나타나지 않는다")
  void orphanAclDoesNotAddRawItem() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    // then
    assertThat(scenario.activePage(SOURCE).items()).isEmpty();
  }

  @Test
  @DisplayName("나노초 시각이 같은 상충 관측도 권한을 넓히지 않는다")
  void submicrosecondConflictCannotExpand() {
    Instant at = T0.plusNanos(100);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(BOB), at), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE, BOB), at), T0);
    // then
    assertThat(scenario.current(SOURCE, ID).keys()).containsExactly(BOB);
  }

  @Test
  @DisplayName("원천 관측 시각은 마이크로초로 내림해 저장한다")
  void observationUsesMicrosecondPrecision() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0.plusNanos(900)), T0);
    // then
    assertThat(scenario.current(SOURCE, ID).observedAt()).isEqualTo(T0);
  }

  @Test
  @DisplayName("비관측 fallback 시각도 마이크로초로 내림해 저장한다")
  void fallbackUsesMicrosecondPrecision() {
    scenario.observe(SOURCE, ID, RawAcl.notObserved(), T0.plusNanos(900));
    // then
    assertThat(scenario.current(SOURCE, ID).observedAt()).isEqualTo(T0);
  }

  @Test
  @DisplayName("미지원 ACL의 같은 원천 scope 키는 보존한다")
  void unsupportedScopeRoundTrips() {
    SourceInstanceId notion = new SourceInstanceId("notion-a");
    AccessKey scope = AccessKey.parse("scope:notion-a:*");
    scenario.observe(notion, ID, RawAcl.observed(Status.UNSUPPORTED, Set.of(scope), T0), T0);
    // then
    assertThat(scenario.current(notion, ID))
        .isEqualTo(new RawAcl.Observed(Status.UNSUPPORTED, Set.of(scope), T0));
  }

  @Test
  @DisplayName("미지원 ACL의 사용자 키는 빈 FAILED로 닫는다")
  void unsupportedUserKeyFailsClosed() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.UNSUPPORTED, Set.of(ALICE), T0), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(RawAcl.failed(AccessFailureReason.INVALID_CARRIER, T0));
  }

  @Test
  @DisplayName("같은 시각의 OK는 기존 FAILED를 되살리지 못한다")
  void equalTimeFailedCannotBecomeOk() {
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T0), T0);
    scenario.observe(SOURCE, ID, RawAcl.observed(Status.OK, Set.of(ALICE), T0), T0);
    // then
    assertThat(scenario.current(SOURCE, ID))
        .isEqualTo(new RawAcl.Observed(Status.FAILED, Set.of(), T0));
  }

  @Test
  @DisplayName("소유 행 없는 실제 문서도 ACL 누락에 센다")
  void countsMissingAclFromUnitsWithoutOwnership() {
    scenario.givenProjectedDocument(SOURCE, "doc-1");
    scenario.withoutOwnership();
    // then
    assertThat(scenario.missing(SOURCE)).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 문서의 여러 유닛은 한 번만 센다")
  void missingAclCountsDistinctDocuments() {
    scenario.givenProjectedDocument(SOURCE, "doc-1");
    scenario.givenProjectedDocument(SOURCE, "doc-1");
    // then
    assertThat(scenario.missing(SOURCE)).isEqualTo(1);
  }

  @Test
  @DisplayName("다른 공간과 출처의 유닛은 누락에 섞지 않는다")
  void missingAclIsSpaceAndSourceScoped() {
    scenario.givenProjectedDocument(OTHER, "doc-1");
    // then
    assertThat(scenario.missing(SOURCE)).isZero();
    assertThat(scenario.missingInOtherSpace(OTHER)).isZero();
  }

  @Test
  @DisplayName("ACL이 있는 실제 문서는 누락에서 뺀다")
  void existingAclIsNotMissing() {
    scenario.givenProjectedDocument(SOURCE, "doc-1");
    scenario.givenDocumentAcl("doc-1");
    // then
    assertThat(scenario.missing(SOURCE)).isZero();
  }

  @Test
  @DisplayName("삭제 뒤 소유 행만 남은 문서는 누락에 세지 않는다")
  void deletedOwnershipDoesNotCountAsMissingAcl() {
    scenario.givenProjectedDocument(SOURCE, "doc-1");
    scenario.deleteProjectedDocument(SOURCE, "doc-1");
    // then
    assertThat(scenario.missing(SOURCE)).isZero();
  }

  @Nested
  @DisplayName("관측과 문서의 만료 처리가 끝난 뒤")
  class AfterExpiryCompleted {
    @BeforeEach
    void givenClosedObservation() {
      scenario.givenItem(SOURCE, ID);
      scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.EXPIRED, T0), T0);
      scenario.givenDocumentAcl("projected");
      scenario.completeExpiry(SOURCE, ID, T0, Optional.of("projected"));
    }

    @Test
    @DisplayName("동일 관측의 만료 후보는 반환하지 않는다")
    void closedObservationIsExcluded() {
      // when
      var page = scenario.expiryCandidates(T0);
      // then
      assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("일반 수집 후보에는 닫힌 관측도 반환한다")
    void collectionStillSeesClosedObservation() {
      // when
      var page =
          scenario.accessCandidates(
              SOURCE, Set.of("drive.file"), Optional.empty(), 10, Optional.empty());
      // then
      assertThat(page.items()).extracting(item -> item.externalId()).containsExactly(ID);
    }

    @Test
    @DisplayName("새 EXPIRED 관측도 다시 만료 후보가 된다")
    void newerExpiredObservationIsIncluded() {
      // given
      scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.EXPIRED, T1), T1);
      // when
      var page = scenario.expiryCandidates(T1);
      // then
      assertThat(page.items()).extracting(item -> item.externalId()).containsExactly(ID);
    }

    @Test
    @DisplayName("원본과 다른 id의 지연 OK 문서도 다시 후보가 된다")
    void lateVisibleDocumentIsIncluded() {
      // given
      scenario.givenVisibleDocument(SpaceId.LEGACY, "projected");
      // when
      var page = scenario.expiryCandidates(T0);
      // then
      assertThat(page.items()).extracting(item -> item.externalId()).containsExactly(ID);
    }

    @Test
    @DisplayName("다른 소스 소유 OK 문서는 닫힌 관측을 다시 후보로 만들지 않는다")
    void foreignOwnerDoesNotReopenCandidate() {
      scenario.givenProjectedDocument(OTHER, "projected");
      scenario.givenVisibleDocument(SpaceId.LEGACY, "projected");
      // then
      assertThat(scenario.expiryCandidates(T0).items()).isEmpty();
    }

    @Test
    @DisplayName("다른 공간 소유 OK 문서는 닫힌 관측을 다시 후보로 만들지 않는다")
    void foreignSpaceOwnerDoesNotReopenCandidate() {
      scenario.givenOwnership(new SpaceId("other"), SOURCE, "projected");
      scenario.givenVisibleDocument(SpaceId.LEGACY, "projected");
      // then
      assertThat(scenario.expiryCandidates(T0).items()).isEmpty();
    }

    @Test
    @DisplayName("만료 후보 선조회는 문서 소유를 선점하지 않는다")
    void candidateReadDoesNotClaimDocument() {
      scenario.givenVisibleDocument(SpaceId.LEGACY, "projected");
      scenario.expiryCandidates(T0);
      scenario.givenProjectedDocument(OTHER, "projected");
      // then
      assertThat(scenario.expiryCandidates(T0).items()).isEmpty();
    }

    @Test
    @DisplayName("같은 소스 소유 지연 OK 문서는 다시 후보가 된다")
    void sameOwnerReopensCandidate() {
      scenario.givenProjectedDocument(SOURCE, "projected");
      scenario.givenVisibleDocument(SpaceId.LEGACY, "projected");
      // then
      assertThat(scenario.expiryCandidates(T0).items())
          .extracting(item -> item.externalId())
          .containsExactly(ID);
    }

    @Test
    @DisplayName("다른 공간의 OK 문서는 닫힌 관측을 다시 후보로 만들지 않는다")
    void otherSpaceDoesNotReopenCandidate() {
      // given
      scenario.givenVisibleDocument(new SpaceId("other"), "projected");
      // when
      var page = scenario.expiryCandidates(T0);
      // then
      assertThat(page.items()).isEmpty();
    }
  }

  @Test
  @DisplayName("처리 완료보다 새로운 관측은 잘못된 완료 요청으로 가려지지 않는다")
  void staleCompletionCannotHideNewObservation() {
    // given
    scenario.givenItem(SOURCE, ID);
    scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.EXPIRED, T1), T1);
    scenario.completeExpiry(SOURCE, ID, T0, Optional.empty());
    // when
    var page = scenario.expiryCandidates(T1);
    // then
    assertThat(page.items()).extracting(item -> item.externalId()).containsExactly(ID);
  }

  @Test
  @DisplayName("EXPIRED 이외의 관측은 완료 요청으로 가려지지 않는다")
  void completionCannotHideOtherFailure() {
    // given
    scenario.givenItem(SOURCE, ID);
    scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, T0), T0);
    scenario.completeExpiry(SOURCE, ID, T0, Optional.empty());
    // when
    var page = scenario.expiryCandidates(T0);
    // then
    assertThat(page.items()).extracting(item -> item.externalId()).containsExactly(ID);
  }

  @Test
  @DisplayName("문서 없는 만료 원본도 다음 후보에서 빠진다")
  void unprojectedExpiryIsExcluded() {
    // given
    scenario.givenItem(SOURCE, ID);
    scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.EXPIRED, T0), T0);
    scenario.completeExpiry(SOURCE, ID, T0, Optional.of("projected"));
    // when
    var page = scenario.expiryCandidates(T0);
    // then
    assertThat(page.items()).isEmpty();
  }

  @Test
  @DisplayName("원본 없는 완료 요청은 나중에 저장한 원본을 가리지 않는다")
  void missingRawCompletionIsIgnored() {
    // given
    scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.EXPIRED, T0), T0);
    scenario.completeExpiry(SOURCE, ID, T0, Optional.empty());
    scenario.givenItem(SOURCE, ID);
    // when
    var page = scenario.expiryCandidates(T0);
    // then
    assertThat(page.items()).extracting(item -> item.externalId()).containsExactly(ID);
  }

  @Test
  @DisplayName("문서 대상이 없는 만료 처리도 완료할 수 있다")
  void expiryWithoutDocumentIsExcluded() {
    // given
    scenario.givenItem(SOURCE, ID);
    scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.EXPIRED, T0), T0);
    scenario.completeExpiry(SOURCE, ID, T0, Optional.empty());
    // when
    var page = scenario.expiryCandidates(T0);
    // then
    assertThat(page.items()).isEmpty();
  }

  @Test
  @DisplayName("같은 id의 다른 소스 관측은 만료 완료로 가려지지 않는다")
  void expiryCompletionIsSourceScoped() {
    // given
    scenario.givenItem(SOURCE, ID);
    scenario.givenItem(OTHER, ID);
    scenario.observe(SOURCE, ID, RawAcl.failed(AccessFailureReason.EXPIRED, T0), T0);
    scenario.observe(OTHER, ID, RawAcl.failed(AccessFailureReason.EXPIRED, T0), T0);
    scenario.completeExpiry(SOURCE, ID, T0, Optional.empty());
    // when
    var page =
        scenario.accessCandidates(
            OTHER, Set.of("drive.file"), Optional.of(T0), 10, Optional.empty());
    // then
    assertThat(page.items()).extracting(item -> item.externalId()).containsExactly(ID);
  }
}
