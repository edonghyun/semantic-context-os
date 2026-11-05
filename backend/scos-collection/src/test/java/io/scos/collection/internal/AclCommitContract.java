package io.scos.collection.internal;

import static io.scos.collection.internal.AclCommitScenario.GRANTED;
import static io.scos.collection.internal.AclCommitScenario.ID;
import static io.scos.collection.internal.AclCommitScenario.OBSERVATION_STEP;
import static io.scos.collection.internal.AclCommitScenario.RUN;
import static io.scos.collection.internal.AclCommitScenario.SOURCE;
import static io.scos.collection.internal.AclCommitScenario.T0;
import static io.scos.collection.internal.AclCommitScenario.T1;
import static io.scos.collection.internal.AclCommitScenario.T2;
import static io.scos.collection.internal.AclCommitScenario.TTL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.RawMutation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근 커밋은 본문·체크포인트와 독립된 ACL 관측을 저장한다")
public abstract class AclCommitContract {
  protected AclCommitScenario scenario;

  protected abstract AclCommitScenario newScenario();

  @BeforeEach
  void setUp() {
    scenario = newScenario();
  }

  @Test
  @DisplayName("이미 만료된 원본은 다시 observe하여 잠그지 않는다")
  void expiredRawDoesNotObserveAgain() {
    scenario.givenExpiredRaw();
    scenario.expireBefore(T0);
    // then
    assertThat(scenario.rawObservations()).isZero();
  }

  @Test
  @DisplayName("만료 이외의 FAILED 원본은 만료 원인으로 닫는다")
  void otherFailureStillExpires() {
    scenario.access(RawAcl.failed(AccessFailureReason.MISSING_TARGET, T1));
    scenario.expireBefore(T1);
    // then
    assertThat(scenario.acl().reason()).isEqualTo(AccessFailureReason.EXPIRED);
  }

  @Test
  @DisplayName("만료 뒤 새로 관측한 OK 원본도 다시 상한에 닫는다")
  void observationAfterExpiryIsExpiredAgain() {
    scenario.givenExpiredRaw();
    scenario.access(RawAcl.observed(Status.OK, GRANTED.keys(), T1));
    scenario.expireBefore(T1);
    // then
    assertThat(scenario.acl().reason()).isEqualTo(AccessFailureReason.EXPIRED);
  }

  @Test
  @DisplayName("접근 커밋은 저장 직전 공용 쓰기 가드를 호출한다")
  void commitUsesWriteFence() {
    scenario.clock.advance(TTL);
    // when·then: 가드 자체의 사례별 계약은 WriteFenceTest에서 검증한다.
    assertThatThrownBy(() -> scenario.access(RawAcl.observed(Status.FAILED, Set.of(), T1)))
        .isInstanceOf(ClaimLostException.class);
  }

  @Test
  @DisplayName("미래 OK 관측 뒤의 정상 좁힘은 즉시 반영된다")
  void futureObservationCannotFenceRestriction() {
    scenario.access(RawAcl.observed(Status.OK, GRANTED.keys(), T2.plusSeconds(3600)));
    scenario.clock.advance(OBSERVATION_STEP);
    scenario.access(RawAcl.observed(Status.OK, Set.of(), scenario.clock.instant()));
    // then
    assertThat(scenario.acl())
        .isEqualTo(new RawAcl.Observed(Status.OK, Set.of(), T2.plusSeconds(1)));
  }

  @Test
  @DisplayName("미래 OK 관측 뒤의 FAILED는 즉시 반영된다")
  void futureObservationCannotFenceFailure() {
    scenario.access(RawAcl.observed(Status.OK, GRANTED.keys(), T2.plusSeconds(3600)));
    scenario.clock.advance(OBSERVATION_STEP);
    scenario.access(RawAcl.observed(Status.FAILED, Set.of(), scenario.clock.instant()));
    // then
    assertThat(scenario.acl())
        .isEqualTo(new RawAcl.Observed(Status.FAILED, Set.of(), T2.plusSeconds(1)));
  }

  @Test
  @DisplayName("AclCommit으로 받은 신원 상충은 실제 병합 모드를 미완료로 만든다")
  void conflictingIdentityForcesIncompleteMergeThroughAclCommit() {
    scenario.domainSharedFileAndVerifiedIdentity();
    scenario.conflictingHiddenIdentityRestrictsFile();
    // then
    assertThat(scenario.mergeAccess()).isEqualTo(AccessMerge.INCOMPLETE);
  }

  @Test
  @DisplayName("의미가 바뀐 ACL은 투영 요청 버전을 한 번 올린다")
  void changedAclRequestsProjection() {
    scenario.access(RawAcl.observed(Status.OK, Set.of(), T1));
    // then
    assertThat(scenario.requestVersion()).isEqualTo(2);
  }

  @Test
  @DisplayName("같은 ACL은 투영 요청을 다시 만들지 않는다")
  void identicalAclDoesNotRequestProjection() {
    scenario.access(RawAcl.observed(Status.OK, GRANTED.keys(), T1));
    // then
    assertThat(scenario.requestVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("접근 커밋은 revision 7을 유지한다")
  void accessCommitDoesNotAdvanceCheckpoint() {
    for (int revision = 0; revision < 7; revision++)
      scenario.ports.checkpoints().advance(SOURCE, revision, new Checkpoint(1, "{}"), T0);
    scenario.access(RawAcl.observed(Status.OK, Set.of(), T1));
    // then
    assertThat(scenario.ports.checkpoints().read(SOURCE).revision()).isEqualTo(7);
  }

  @Test
  @DisplayName("다른 인스턴스 키는 관측 시각을 유지한 FAILED가 된다")
  void wrongInstanceBecomesFailed() {
    scenario.access(RawAcl.observed(Status.OK, Set.of(AccessKey.parse("user:other:U1")), T1));
    // then
    assertThat(scenario.acl())
        .isEqualTo(
            new RawAcl.Observed(Status.FAILED, Set.of(), T1, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("지원 원천의 scope 키는 FAILED가 된다")
  void scopeOnSupportedSourceBecomesFailed() {
    scenario.access(RawAcl.observed(Status.OK, Set.of(AccessKey.parse("scope:acl-source:*")), T1));
    // then
    assertThat(scenario.acl())
        .isEqualTo(
            new RawAcl.Observed(Status.FAILED, Set.of(), T1, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("지원 원천의 UNSUPPORTED 상태는 FAILED가 된다")
  void unsupportedStatusOnSupportedSourceIsFailed() {
    scenario.access(
        RawAcl.observed(Status.UNSUPPORTED, Set.of(AccessKey.parse("scope:acl-source:*")), T1));
    // then
    assertThat(scenario.acl())
        .isEqualTo(
            new RawAcl.Observed(Status.FAILED, Set.of(), T1, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("과거 FAILED는 최신 허용 ACL을 되돌리지 않는다")
  void staleObservationCannotDowngradeNewerAcl() {
    scenario
        .ports
        .rawAcls()
        .observe(SOURCE, ID, RawAcl.observed(Status.OK, GRANTED.keys(), T2), T2);
    scenario.access(RawAcl.observed(Status.FAILED, Set.of(), T1));
    // then
    assertThat(scenario.acl()).isEqualTo(new RawAcl.Observed(Status.OK, GRANTED.keys(), T2));
  }

  @Test
  @DisplayName("접근 403·404의 FAILED 관측은 active 원본을 삭제하지 않는다")
  void forbiddenObservationFailsWithoutDeletingRaw() {
    scenario.access(RawAcl.observed(Status.FAILED, Set.of(), T1));
    // then
    assertThat(scenario.ports.rawItems().get(SOURCE, ID).orElseThrow())
        .isInstanceOf(RawRecord.Active.class);
    // then
    assertThat(scenario.acl()).isEqualTo(new RawAcl.Observed(Status.FAILED, Set.of(), T1));
  }

  @Test
  @DisplayName("비관측은 기존 ACL을 보존한다")
  void notObservedPreservesAcl() {
    scenario.access(RawAcl.notObserved());
    // then
    assertThat(scenario.acl()).isEqualTo(GRANTED);
  }

  @Test
  @DisplayName("collect=false는 접근 ACL을 저장하지 않는다")
  void disabledCollectionPreservesAcl() {
    scenario
        .accesses(false)
        .commit(
            scenario.claim,
            RUN,
            scenario.source,
            new AclPage(
                Map.of(ID, RawAcl.observed(Status.FAILED, Set.of(), T1)), List.of(), List.of()));
    // then
    assertThat(scenario.acl()).isEqualTo(GRANTED);
  }

  @Test
  @DisplayName("비운반 원본은 ACL 행을 만들지 않는다")
  void nonCarrierDoesNotCreateAcl() {
    scenario
        .ports
        .rawItems()
        .apply(SOURCE, RawItems.item("fragment").resourceType("slack.fragment").upsert());
    scenario
        .accesses(true)
        .commit(
            scenario.claim,
            RUN,
            scenario.source,
            new AclPage(Map.of("fragment", GRANTED), List.of(), List.of()));
    // then
    assertThat(scenario.ports.rawAcls().get(SOURCE, List.of("fragment"))).isEmpty();
  }

  @Test
  @DisplayName("없는 원본은 ACL 행을 만들지 않는다")
  void missingRawDoesNotCreateAcl() {
    scenario
        .accesses(true)
        .commit(
            scenario.claim,
            RUN,
            scenario.source,
            new AclPage(Map.of("missing", GRANTED), List.of(), List.of()));
    // then
    assertThat(scenario.ports.rawAcls().get(SOURCE, List.of("missing"))).isEmpty();
  }

  @Test
  @DisplayName("삭제된 원본은 접근 관측으로 ACL을 되살리지 않는다")
  void deletedRawDoesNotCreateAcl() {
    scenario
        .ports
        .rawItems()
        .apply(
            SOURCE,
            new RawMutation.Delete(
                "deleted", Optional.empty(), Optional.empty(), Optional.empty(), T0));
    scenario
        .accesses(true)
        .commit(
            scenario.claim,
            RUN,
            scenario.source,
            new AclPage(Map.of("deleted", GRANTED), List.of(), List.of()));
    // then
    assertThat(scenario.ports.rawAcls().get(SOURCE, List.of("deleted"))).isEmpty();
  }

  @Test
  @DisplayName("같은 시각 신원 상충 페이지도 원본 ACL 회수와 요청을 보존한다")
  void conflictingObservationKeepsRawAclRestriction() {
    scenario.domainSharedFileAndVerifiedIdentity();
    scenario.conflictingHiddenIdentityRestrictsFile();
    // then: stage의 상충도 ACL 회수와 요청의 커밋을 막지 않는다.
    assertThat(
            List.of(
                scenario.ports.rawAcls().get(SOURCE, List.of("F1")).get("F1"),
                scenario.requestVersion()))
        .containsExactly(new RawAcl.Observed(Status.OK, Set.of(), T1), 2L);
  }

  @Test
  @DisplayName("접근 수집이 꺼져 있으면 투영 요청을 다시 만들지 않는다")
  void disabledAccessDoesNotRequestProjection() {
    scenario
        .accesses(false)
        .commit(
            scenario.claim,
            RUN,
            scenario.source,
            new AclPage(
                Map.of(ID, RawAcl.observed(Status.FAILED, Set.of(), T1)), List.of(), List.of()));
    // then
    assertThat(scenario.requestVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("비운반 원본의 접근 관측은 투영 요청을 다시 만들지 않는다")
  void nonCarrierAccessDoesNotRequestProjection() {
    scenario
        .ports
        .rawItems()
        .apply(SOURCE, RawItems.item("fragment").resourceType("slack.fragment").upsert());
    scenario
        .accesses(true)
        .commit(
            scenario.claim,
            RUN,
            scenario.source,
            new AclPage(Map.of("fragment", GRANTED), List.of(), List.of()));
    // then
    assertThat(scenario.requestVersion()).isEqualTo(1);
  }
}
