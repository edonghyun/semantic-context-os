package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.admin.AdminDiagnostics.CarrierAccess;
import io.scos.engine.admin.AdminDiagnostics.RunCost;
import io.scos.engine.admin.AdminDiagnostics.SourceAccessView;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.connector.RawAcl;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리 접근 진단은 저장된 원인과 실제 사용자 키를 보인다(I4·I5)")
class AdminAccessQueriesTest {
  private final AdminAccessQueriesScenario scenario = new AdminAccessQueriesScenario();

  @Test
  @DisplayName("미관측 수동 연결은 연결 없음과 구분된다")
  void manualUnobservedLinkIsDistinguishedFromUnlinkedIdentity() {
    // given
    scenario.givenManualUnobservedLink();
    // when
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.links())
        .singleElement()
        .extracting(IdentityLink::warnings)
        .isEqualTo(Set.of("unobserved"));
    assertThat(result.keys()).isEmpty();
  }

  @Test
  @DisplayName("다른 신원의 guest 관측을 미관측 수동 연결의 경고로 빌리지 않는다")
  void warningDoesNotBorrowAnotherIdentityObservation() {
    scenario.givenManualUnobservedLink();
    scenario.givenAnotherGuestObservation();
    // when
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.links())
        .singleElement()
        .extracting(IdentityLink::warnings)
        .isEqualTo(Set.of("unobserved"));
  }

  @Test
  @DisplayName("guest 수동 연결은 경고되지만 키를 부여하지 않는다")
  void guestAdminLinkWarnsWithoutGrant() {
    // given
    scenario.givenGuestAdminLink();
    // when
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.links())
        .singleElement()
        .extracting(IdentityLink::warnings)
        .isEqualTo(Set.of("guest"));
    assertThat(result.keys()).isEmpty();
    assertThat(result.visible()).isFalse();
  }

  @Test
  @DisplayName("삭제된 수동 연결은 경고되지만 키를 부여하지 않는다")
  void deletedAdminLinkWarnsWithoutGrant() {
    // given
    scenario.givenDeletedAdminLink();
    // when
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.links())
        .singleElement()
        .extracting(IdentityLink::warnings)
        .isEqualTo(Set.of("deleted"));
    assertThat(result.keys()).isEmpty();
  }

  @Test
  @DisplayName("원천 조회는 본문 성공과 접근 미완료를 각각 보인다")
  void sourceStatusIncludesIndependentBodyAndAccessResults() {
    // when
    var result = scenario.sourceAccess();
    // then
    assertThat(result)
        .singleElement()
        .extracting(SourceAccessView::bodyResult, SourceAccessView::result)
        .containsExactly(Optional.of("succeeded"), Optional.of("incomplete"));
  }

  @Test
  @DisplayName("원천 조회 비용은 같은 run 식별자와 집계 시각을 보존한다")
  void sourceStatusExposesRunCostWithRunIdentity() {
    // when
    var cost = scenario.sourceAccess().getFirst().cost().orElseThrow();
    // then
    assertThat(cost)
        .extracting(
            RunCost::runId,
            RunCost::recordedAt,
            value -> value.cost().targetCount(),
            value -> value.cost().callCount().orElseThrow(),
            value -> value.cost().elapsedMs())
        .containsExactly("run-r1", AdminAccessQueriesScenario.T0, 2L, 4L, 1250L);
  }

  @Test
  @DisplayName("실행 중인 원천도 직전 미완료 추세와 마지막 완료를 보존한다")
  void incompleteTrendIsVisibleWhileAnotherRunIsRunning() {
    // given
    scenario.givenRunningRun();
    // when
    var result = scenario.sourceAccess().getFirst();
    // then
    assertThat(result)
        .extracting(
            SourceAccessView::running,
            SourceAccessView::consecutiveIncomplete,
            SourceAccessView::lastCompleteAt)
        .containsExactly(true, 2, Optional.of(AdminAccessQueriesScenario.T0));
  }

  @Test
  @DisplayName("원천 상태의 모든 보충 집계는 관리 조회에 보존된다")
  void sourceAccessPreservesSupplementaryCounts() {
    // when
    var result = scenario.sourceAccess().getFirst();
    // then
    assertThat(result)
        .extracting(
            value -> value.source().value(),
            SourceAccessView::kind,
            SourceAccessView::finishedAt,
            SourceAccessView::rawUnknown,
            SourceAccessView::rawFailed,
            SourceAccessView::documentsMissingAcl,
            SourceAccessView::rawUnobserved,
            SourceAccessView::rawStale,
            SourceAccessView::oldestObservedAt,
            SourceAccessView::documentsHidden,
            SourceAccessView::unreadableFiles)
        .containsExactly(
            "drive-a",
            "google-drive",
            Optional.of(AdminAccessQueriesScenario.T0),
            1,
            2,
            0,
            3,
            4,
            Optional.of(AdminAccessQueriesScenario.T0),
            2,
            Set.of("file:F1"));
  }

  @Test
  @DisplayName("관측되지 않은 연결과 연결 부재는 별도로 보인다")
  void diagnosisSeparatesUnlinkedFromUnobservedIdentity() {
    // when
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.links()).isEmpty();
    assertThat(result.visible()).isFalse();
  }

  @Test
  @DisplayName("진단 키와 교집합은 실제 신원 해석 결과를 사용한다")
  void diagnosisUsesActualResolverKeys() {
    // given
    scenario.givenObservedReader();
    // when
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.keys())
        .extracting(Object::toString)
        .containsExactly("user:drive-a:reader@example.com");
    assertThat(result.intersection())
        .extracting(Object::toString)
        .containsExactly("user:drive-a:reader@example.com");
    assertThat(result.visible()).isTrue();
  }

  @Test
  @DisplayName("누락 대상 원인은 저장된 문서와 carrier에 보인다")
  void diagnosisSeparatesMissingTargetFromExpiredAcl() {
    // given
    scenario.givenFailedAcl(AccessFailureReason.MISSING_TARGET);
    // when
    var result = scenario.explain(Optional.empty()).orElseThrow();
    // then
    assertThat(result.acl().orElseThrow().reason().wire()).isEqualTo("missing_target");
    assertThat(result.carriers())
        .singleElement()
        .extracting(
            CarrierAccess::externalId, value -> ((RawAcl.Observed) value.acl()).reason().wire())
        .containsExactly("file:F1", "missing_target");
  }

  @Test
  @DisplayName("만료된 ACL의 원인은 expired로 보인다")
  void diagnosisPreservesExpiredAclReason() {
    // given
    scenario.givenFailedAcl(AccessFailureReason.EXPIRED);
    // when
    var result = scenario.explain(Optional.empty()).orElseThrow();
    // then
    assertThat(result.acl().orElseThrow().reason().wire()).isEqualTo("expired");
  }

  @Test
  @DisplayName("문서 ACL이 없으면 사용자에게 보이지 않는다")
  void missingDocumentAclDoesNotGrantAccess() {
    // given
    scenario.givenObservedReader();
    scenario.givenMissingAcl();
    // when
    var result = scenario.explain(Optional.of("reader")).orElseThrow();
    // then
    assertThat(result.acl()).isEmpty();
    assertThat(result.user().orElseThrow().visible()).isFalse();
  }

  @Test
  @DisplayName("사용자 없이 진단하면 소유만 반환한다")
  void diagnosisWithoutUserPreservesOwnership() {
    // when
    var result = scenario.explain(Optional.empty()).orElseThrow();
    // then
    assertThat(result)
        .extracting(
            value -> value.source().value(),
            value -> value.space().value(),
            value -> value.documentId())
        .containsExactly("drive-a", "legacy", "doc");
    assertThat(result.user()).isEmpty();
  }

  @Test
  @DisplayName("실행 전 원천의 결과와 비용은 미측정으로 보인다")
  void neverRunStatusHasNoInventedResultOrCost() {
    // given
    scenario.givenNeverRun();
    // when
    var result = scenario.sourceAccess().getFirst();
    // then
    assertThat(result)
        .extracting(
            SourceAccessView::bodyResult,
            SourceAccessView::result,
            SourceAccessView::finishedAt,
            SourceAccessView::cost)
        .containsExactly(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  @Test
  @DisplayName("진단은 다음 페이지에 있는 사용자 연결도 포함한다")
  void diagnosisIncludesLinksBeyondFirstPage() {
    // given
    scenario.givenManyLinks();
    // when
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.links())
        .hasSize(51)
        .extracting(IdentityLink::sourceUserId)
        .contains("reader-50@example.com");
  }

  @Test
  @DisplayName("다른 원천의 연결은 문서 원천의 연결로 보고하지 않는다")
  void diagnosisExcludesOtherSourceLinks() {
    // given
    scenario.givenObservedReader();
    scenario.givenOtherSourceLink();
    // when
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.links())
        .singleElement()
        .extracting(link -> link.source().value(), IdentityLink::warnings)
        .containsExactly("drive-a", Set.of());
  }

  @Test
  @DisplayName("없는 사용자는 알려진 사용자로 보고하지 않는다")
  void diagnosisPreservesUnknownUser() {
    // when
    var result = scenario.explain(Optional.of("absent")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result)
        .extracting(
            value -> value.id(),
            value -> value.known(),
            value -> value.keys(),
            value -> value.visible())
        .containsExactly("absent", false, Set.of(), false);
  }

  @Test
  @DisplayName("없는 문서의 진단은 없다")
  void unknownDocumentHasNoDiagnosis() {
    // given
    scenario.givenUnknownDocument();
    // when
    var result = scenario.explain(Optional.empty());
    // then
    assertThat(result).isEmpty();
  }
}
