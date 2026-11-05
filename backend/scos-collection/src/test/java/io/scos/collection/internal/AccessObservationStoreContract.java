package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.AclPage;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** 그룹·신원 쓰기의 원자성 및 접근 제한 계약을 메모리와 PostgreSQL에 똑같이 적용한다. */
@DisplayName("접근 관측 저장소는 전체성·시각 단조·회수 계약을 지킨다")
public abstract class AccessObservationStoreContract {
  public static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  public static final Instant T1 = T0.plusSeconds(1);
  public static final Instant T2 = T0.plusSeconds(2);
  protected AccessObservationScenario scenario;

  protected abstract AccessObservationScenario newScenario();

  @BeforeEach
  void prepare() {
    scenario = newScenario();
  }

  @Test
  @DisplayName("보관만 한 그룹은 읽기 키를 늘리지 않는다")
  void stagedGroupsDoNotGrantAccess() {
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    // then
    assertThat(scenario.keys()).isEmpty();
  }

  @Test
  @DisplayName("없는 run의 접근 관측은 보관하지 않는다")
  void missingRunCannotStage() {
    // when·then
    assertThatThrownBy(
            () ->
                scenario.store.stage(
                    new RunId("missing-run"), new AclPage(Map.of(), List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("run의 원천과 다른 원천으로는 병합하지 않는다")
  void mergeRejectsDifferentRunSource() {
    // when·then
    assertThatThrownBy(
            () ->
                scenario.store.merge(
                    scenario.run,
                    new SourceInstanceId("foreign"),
                    AccessMerge.COMPLETE,
                    T2,
                    () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("그룹 확정은 다른 소스의 같은 그룹을 읽지 않는다")
  void groupMergeIsSourceScoped() {
    scenario.seedForeignAccess();
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    // when
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("신원 확정은 다른 소스의 같은 신원을 읽지 않는다")
  void identityMergeIsSourceScoped() {
    scenario.seedForeignAccess();
    scenario.stage(identity("U2", T1));
    // when
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.identity("U2").deleted()).isFalse();
  }

  @Test
  @DisplayName("그룹의 지연 페이지는 최신 전체 스냅샷을 덮지 않는다")
  void duplicateGroupSnapshotKeepsLatest() {
    scenario.stage(group("C1", T0, "U1", "U2"));
    scenario.stage(group("C1", T2, "U2"), identity("U2", T2));
    scenario.stage(group("C1", T1, "U1", "U2", "U3"));
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1")).isEqualTo(new GroupSnapshot("C1", Set.of("U2"), T2));
  }

  @Test
  @DisplayName("신원의 지연 페이지는 삭제 관측을 복원하지 않는다")
  void duplicateIdentityKeepsLatest() {
    scenario.stage(new SourceIdentityRecord("U2", Optional.empty(), false, false, true, T2));
    scenario.stage(identity("U2", T1));
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.identity("U2").deleted()).isTrue();
  }

  @Test
  @DisplayName("동시각 동일 그룹 재전송은 상충 없는 한 행이다")
  void equalTimeIdenticalSnapshotIsIdempotent() {
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    // then
    assertThat(scenario.staged())
        .isEqualTo(
            new AccessMergePolicy.Observations(
                new SourceAccessDirectory.Snapshot(
                    Map.of(
                        "U2",
                        new SourceIdentityRecord("U2", Optional.empty(), false, false, false, T1)),
                    Map.of("C1", new GroupSnapshot("C1", Set.of("U2"), T1))),
                false));
  }

  @Test
  @DisplayName("동시각 상충 그룹은 예외 없이 교집합으로 보관한다")
  void equalTimeConflictResolvesToNarrower() {
    scenario.stage(group("C1", T1, "U1", "U2"));
    scenario.stage(group("C1", T1, "U2", "U3"));
    // then
    assertThat(scenario.staged().snapshot().groups().get("C1"))
        .isEqualTo(new GroupSnapshot("C1", Set.of("U2"), T1));
  }

  @ParameterizedTest
  @CsvSource({
    "true,false,true,true,false,true",
    "false,true,true,false,true,true",
    "false,false,false,false,false,false"
  })
  @DisplayName("상충 신원은 guest와 deleted를 OR, verified를 AND로 제한한다")
  void identityConflictOnlyRestricts(
      boolean guest,
      boolean deleted,
      boolean verified,
      boolean expectedGuest,
      boolean expectedDeleted,
      boolean expectedVerified) {
    scenario.stage(
        new SourceIdentityRecord("U2", Optional.of("u2@example.com"), true, false, false, T1));
    scenario.stage(
        new SourceIdentityRecord(
            "U2", Optional.of("u2@example.com"), verified, guest, deleted, T1));
    // then
    assertThat(scenario.staged().snapshot().identities().get("U2"))
        .isEqualTo(
            new SourceIdentityRecord(
                "U2",
                Optional.of("u2@example.com"),
                expectedVerified,
                expectedGuest,
                expectedDeleted,
                T1));
  }

  @ParameterizedTest
  @CsvSource({"u3@example.com", "''"})
  @DisplayName("동시각의 서로 다른 이메일은 빈 값으로 보관한다")
  void identityConflictClearsDifferentEmail(String email) {
    scenario.stage(
        new SourceIdentityRecord("U2", Optional.of("u2@example.com"), true, false, false, T1));
    scenario.stage(
        new SourceIdentityRecord(
            "U2", email.isEmpty() ? Optional.empty() : Optional.of(email), true, false, false, T1));
    // then
    assertThat(scenario.staged().snapshot().identities().get("U2").email()).isEmpty();
  }

  @Test
  @DisplayName("상충 표식은 COMPLETE 요청도 INCOMPLETE로 내린다")
  void conflictForcesIncompleteMerge() {
    scenario.conflict();
    // then
    assertThat(scenario.merge(AccessMerge.COMPLETE)).isEqualTo(AccessMerge.INCOMPLETE);
  }

  @Test
  @DisplayName("상충 관측은 기존 그룹을 늘릴 수 없다")
  void conflictCannotExpandExistingGroup() {
    scenario.existing();
    scenario.conflict();
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("뒤의 최신 정상 payload도 run 상충 표식을 지우지 않는다")
  void laterSnapshotDoesNotClearRunConflict() {
    scenario.conflict();
    scenario.stage(group("C1", T2, "U2"), identity("U2", T2));
    // then
    assertThat(scenario.merge(AccessMerge.COMPLETE)).isEqualTo(AccessMerge.INCOMPLETE);
  }

  @Test
  @DisplayName("완료한 전체 집합은 관측 그룹 구성원을 교체한다")
  void completeReplacesMembers() {
    scenario.existing();
    scenario.observed();
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1").members()).containsExactlyInAnyOrder("U2", "U3");
  }

  @Test
  @DisplayName("완료한 전체 집합은 미관측 그룹을 종료 시각으로 비운다")
  void completeClearsUnobservedGroup() {
    scenario.existing();
    scenario.observed();
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C2")).isEqualTo(new GroupSnapshot("C2", Set.of(), T2));
  }

  @Test
  @DisplayName("미완료 집합은 관측 그룹의 교집합만 반영한다")
  void incompleteOnlyIntersectsObservedGroup() {
    scenario.existing();
    scenario.observed();
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.group("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("미완료 집합은 새 그룹을 만들지 않는다")
  void incompleteCannotCreateGroup() {
    scenario.stage(group("C3", T1, "U2"), identity("U2", T1));
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.groups()).doesNotContainKey("C3");
  }

  @Test
  @DisplayName("미완료 집합은 미관측 그룹을 유지한다")
  void incompleteKeepsUnobservedGroup() {
    scenario.existing();
    scenario.observed();
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.group("C2")).isEqualTo(new GroupSnapshot("C2", Set.of("U1"), T0));
  }

  @ParameterizedTest
  @CsvSource({"true,false", "false,true"})
  @DisplayName("미완료 신원은 guest와 deleted의 true만 반영하고 되돌리지 않는다")
  void incompleteOnlyRestrictsIdentity(boolean guest, boolean deleted) {
    scenario.stage(new SourceIdentityRecord("U2", Optional.empty(), false, guest, deleted, T0));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(identity("U2", T1));
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.identity("U2"))
        .isEqualTo(new SourceIdentityRecord("U2", Optional.empty(), false, guest, deleted, T1));
  }

  @ParameterizedTest
  @CsvSource({"true,false", "false,true"})
  @DisplayName("미완료 신원의 새 guest 또는 deleted 제한도 저장한다")
  void incompleteAppliesIdentityRestriction(boolean guest, boolean deleted) {
    scenario.stage(identity("U2", T0));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(new SourceIdentityRecord("U2", Optional.empty(), false, guest, deleted, T1));
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.identity("U2"))
        .isEqualTo(new SourceIdentityRecord("U2", Optional.empty(), false, guest, deleted, T1));
  }

  @Test
  @DisplayName("미완료 신원은 새 이메일을 추가하지 않는다")
  void incompleteCannotAddEmail() {
    scenario.existing();
    scenario.stage(
        new SourceIdentityRecord("U2", Optional.of("u2@example.com"), true, false, false, T1));
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.identity("U2").email()).isEmpty();
  }

  @Test
  @DisplayName("미완료 신원은 이메일 검증을 복원하지 않는다")
  void incompleteCannotVerifyEmail() {
    scenario.stage(
        new SourceIdentityRecord("U2", Optional.of("u2@example.com"), false, false, false, T0));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(
        new SourceIdentityRecord("U2", Optional.of("u2@example.com"), true, false, false, T1));
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.identity("U2").emailVerified()).isFalse();
  }

  @Test
  @DisplayName("이전 run의 오래된 신원은 삭제 상태를 복원하지 않는다")
  void olderIdentityCannotRestoreAccess() {
    scenario.stage(new SourceIdentityRecord("U2", Optional.empty(), false, false, true, T2));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(identity("U2", T1));
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.identity("U2"))
        .isEqualTo(new SourceIdentityRecord("U2", Optional.empty(), false, false, true, T2));
  }

  @Test
  @DisplayName("이메일 숨김 신원도 미리 연결한 사용자에게 user 키를 준다")
  void completeObservesHiddenEmailIdentity() {
    scenario.stage(identity("U2", T1));
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.keys()).containsExactly("user:access-source:U2");
  }

  @Test
  @DisplayName("구성원 신원이 빠지면 COMPLETE 확장 없이 INCOMPLETE를 반환한다")
  void memberWithoutIdentityPreventsCompletion() {
    scenario.existing();
    scenario.stage(group("C1", T1, "U2", "U3"), identity("U2", T1));
    // then
    assertThat(scenario.merge(AccessMerge.COMPLETE)).isEqualTo(AccessMerge.INCOMPLETE);
  }

  @Test
  @DisplayName("전체성 위반 뒤에도 유효한 회수는 별도 병합으로 반영한다")
  void completenessViolationStillNarrows() {
    scenario.incompleteSnapshot();
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("전체성 위반의 실제 적용 모드는 INCOMPLETE다")
  void completenessViolationReturnsIncomplete() {
    scenario.incompleteSnapshot();
    // then
    assertThat(scenario.merge(AccessMerge.COMPLETE)).isEqualTo(AccessMerge.INCOMPLETE);
  }

  @Test
  @DisplayName("전체성 fallback은 미관측 그룹을 비우지 않는다")
  void incompleteFallbackKeepsUnobservedGroup() {
    scenario.incompleteSnapshot();
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C2")).isEqualTo(new GroupSnapshot("C2", Set.of("U1"), T0));
  }

  @ParameterizedTest
  @CsvSource({"~members,~other", "C3,C4", "~members,C4"})
  @DisplayName("그룹 참조 위반은 그 그룹의 새 구성원을 추가하지 않는다")
  void invalidGroupReferenceCannotExpand(String group, String reference) {
    scenario.stage(group(group, T0, "U2"), identity("U2", T0));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(group(group, T1, reference, "U2", "U3"));
    scenario.stage(group(reference, T1, "U2"), identity("U2", T1), identity("U3", T1));
    // when
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group(group)).isEqualTo(group(group, T1, "U2"));
  }

  @ParameterizedTest
  @CsvSource({"~members,~other", "C3,C4"})
  @DisplayName("그룹 참조 위반이 있어도 같은 run의 다른 그룹 회수는 저장한다")
  void invalidGroupReferenceStillAppliesOtherRevocation(String group, String reference) {
    scenario.existing();
    scenario.stage(group(group, T1, reference), group(reference, T1, "U2"), identity("U2", T1));
    scenario.stage(group("C1", T1, "U2"));
    // when
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1")).isEqualTo(new GroupSnapshot("C1", Set.of("U2"), T1));
  }

  @ParameterizedTest
  @CsvSource({"~members,~other", "C3,C4"})
  @DisplayName("그룹 참조 위반은 실제 접근 병합 결과를 INCOMPLETE로 내린다")
  void invalidGroupReferenceReturnsIncomplete(String group, String reference) {
    scenario.stage(group(group, T1, reference), group(reference, T1, "U2"), identity("U2", T1));
    // when
    var result = scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(result).isEqualTo(AccessMerge.INCOMPLETE);
  }

  @Test
  @DisplayName("실제 그룹은 같은 소스 합성 그룹 한 단계만 참조한다")
  void realGroupCanReferenceSyntheticGroup() {
    scenario.stage(group("C1", T1, "~members"), group("~members", T1, "U2"), identity("U2", T1));
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.keys())
        .containsExactlyInAnyOrder(
            "user:access-source:U2", "group:access-source:~members", "group:access-source:C1");
  }

  @Test
  @DisplayName("참조 위반 그룹만 제한하며 정상 관측한 그룹 변경은 그대로 적용한다")
  void invalidGroupDoesNotRestrictValidGroupChanges() {
    scenario.stage(group("C3", T1, "C4"), group("C4", T1, "U2"), identity("U2", T1));
    // when
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C4")).isEqualTo(new GroupSnapshot("C4", Set.of("U2"), T1));
  }

  @Test
  @DisplayName("참조 위반인 새 그룹은 생성하지 않는다")
  void invalidNewGroupIsNotCreated() {
    scenario.stage(group("C3", T1, "C4"), group("C4", T1, "U2"), identity("U2", T1));
    // when
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.groups()).doesNotContainKey("C3");
  }

  @ParameterizedTest
  @EnumSource(AccessMerge.class)
  @DisplayName("기존 구성원이 실제 그룹으로 관측되면 그 참조도 버린다")
  void identityObservedAsGroupLosesMembership(AccessMerge mode) {
    scenario.stage(group("C3", T0, "C4"), identity("C4", T0));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(group("C3", T1, "C4"), group("C4", T1, "U2"), identity("U2", T1));
    // when
    scenario.merge(mode);
    // then
    assertThat(scenario.group("C3")).isEqualTo(new GroupSnapshot("C3", Set.of(), T1));
  }

  @Test
  @DisplayName("완료 병합 전에 guard가 실패하면 쓰지 않는다")
  void failedGuardWritesNothing() {
    scenario.existing();
    scenario.observed();
    // then
    assertThatThrownBy(
            () ->
                scenario.merge(
                    AccessMerge.COMPLETE,
                    () -> {
                      throw new IllegalStateException("lost");
                    }))
        .isInstanceOf(IllegalStateException.class);
    // then
    assertThat(scenario.group("C1")).isEqualTo(new GroupSnapshot("C1", Set.of("U1", "U2"), T0));
  }

  @Test
  @DisplayName("fallback도 guard를 다시 검사하므로 점유 상실이면 쓰지 않는다")
  void fallbackGuardWritesNothing() {
    scenario.incompleteSnapshot();
    AtomicInteger attempts = new AtomicInteger();
    // then
    assertThatThrownBy(
            () ->
                scenario.merge(
                    AccessMerge.COMPLETE,
                    () -> {
                      if (attempts.incrementAndGet() == 2)
                        throw new IllegalStateException("lost in fallback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    // then
    assertThat(scenario.group("C1")).isEqualTo(new GroupSnapshot("C1", Set.of("U1", "U2"), T0));
  }

  @Test
  @DisplayName("실패한 병합은 보관 관측과 상충을 보존한다")
  void failedMergeKeepsObservations() {
    scenario.conflict();
    try {
      scenario.merge(
          AccessMerge.COMPLETE,
          () -> {
            throw new IllegalStateException("lost");
          });
    } catch (IllegalStateException expected) {
      /* retry below */
    }
    // then
    assertThat(scenario.merge(AccessMerge.COMPLETE)).isEqualTo(AccessMerge.INCOMPLETE);
  }

  @Test
  @DisplayName("폐기는 해당 run의 관측과 상충만 없앤다")
  void discardRemovesRunConflict() {
    scenario.conflict();
    scenario.discard();
    scenario.stage(identity("U2", T1));
    // then
    assertThat(scenario.merge(AccessMerge.COMPLETE)).isEqualTo(AccessMerge.COMPLETE);
  }

  @Test
  @DisplayName("오래된 그룹 관측은 새 run에서도 저장된 구성원을 복원하지 않는다")
  void olderGroupCannotRestoreAccess() {
    scenario.stage(group("C1", T2, "U2"), identity("U2", T2));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(group("C1", T1, "U1", "U2"), identity("U1", T1));
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1")).isEqualTo(new GroupSnapshot("C1", Set.of("U2"), T2));
  }

  @Test
  @DisplayName("오래된 전체 집합 종료 시각은 미래의 미관측 그룹을 비우지 않는다")
  void olderCompletionCannotClearNewerGroup() {
    scenario.stage(group("C1", T2.plusSeconds(1), "U2"), identity("U2", T2));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("서로 다른 run의 동시각 그룹 상충도 교집합이다")
  void equalTimeCommittedGroupOnlyRestricts() {
    scenario.existing();
    scenario.stage(group("C1", T0, "U2", "U3"), identity("U2", T0), identity("U3", T0));
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("같은 의미의 늦은 그룹 관측도 고수위를 전진한다")
  void identicalGroupAdvancesObservationFence() {
    scenario.existing();
    scenario.stage(group("C1", T2, "U1", "U2"), identity("U1", T2), identity("U2", T2));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.group("C1")).isEqualTo(new GroupSnapshot("C1", Set.of("U1", "U2"), T2));
  }

  @Test
  @DisplayName("완료한 새 신원 관측은 guest·삭제를 해제하고 검증된 이메일을 복원한다")
  void completeCanRestoreIdentity() {
    scenario.stage(new SourceIdentityRecord("U2", Optional.empty(), false, true, true, T0));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(
        new SourceIdentityRecord("U2", Optional.of("u2@example.com"), true, false, false, T1));
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.identity("U2"))
        .isEqualTo(
            new SourceIdentityRecord("U2", Optional.of("u2@example.com"), true, false, false, T1));
  }

  @Test
  @DisplayName("미완료 집합은 새 신원을 추가하지 않는다")
  void incompleteCannotCreateIdentity() {
    scenario.stage(identity("U2", T1));
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.current().identities()).isEmpty();
  }

  @Test
  @DisplayName("완료 집합에서 누락된 신원은 이전 관측을 보존한다")
  void completeKeepsUnobservedIdentity() {
    scenario.existing();
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.identity("U2"))
        .isEqualTo(new SourceIdentityRecord("U2", Optional.empty(), false, false, false, T0));
  }

  @Test
  @DisplayName("미관측 합성 그룹 참조가 있으면 전체성 fallback을 적용한다")
  void missingSyntheticGroupPreventsCompletion() {
    scenario.stage(group("C1", T1, "~members"));
    // then
    assertThat(scenario.merge(AccessMerge.COMPLETE)).isEqualTo(AccessMerge.INCOMPLETE);
  }

  @Test
  @DisplayName("서로 다른 run의 동시각 신원 상충은 복원 없이 제한한다")
  void equalTimeCommittedIdentityOnlyRestricts() {
    scenario.stage(
        new SourceIdentityRecord("U2", Optional.of("u2@example.com"), true, true, true, T1));
    scenario.merge(AccessMerge.COMPLETE);
    scenario.nextRun();
    scenario.stage(identity("U2", T1));
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.identity("U2"))
        .isEqualTo(new SourceIdentityRecord("U2", Optional.empty(), false, true, true, T1));
  }

  @Test
  @DisplayName("run 폐기는 완료된 다른 run의 관측과 상충을 남긴다")
  void discardPreservesOtherRun() {
    scenario.conflict();
    RunId previous = scenario.run;
    scenario.finish();
    scenario.nextRun();
    scenario.stage(identity("U2", T1));
    scenario.discard();
    scenario.run = previous;
    // then
    assertThat(scenario.merge(AccessMerge.COMPLETE)).isEqualTo(AccessMerge.INCOMPLETE);
  }

  @Test
  @DisplayName("확정 그룹 지문은 정렬된 구성원 전체를 반영한다")
  void groupFingerprintReflectsMembers() {
    scenario.existing();
    // then
    assertThat(scenario.fingerprint("C1"))
        .isEqualTo("51347eaaf8910d14e2382d8b47b9a874d5a65242f70bda5fe76072a961643a69");
  }

  @Test
  @DisplayName("교집합 확정은 제한된 구성원으로 지문을 바꾼다")
  void restrictionUpdatesGroupFingerprint() {
    scenario.existing();
    scenario.observed();
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.fingerprint("C1"))
        .isEqualTo("df46ed4017d440f851f021b33f65bb4741d4458df79786c2c30ade9f222f5fd4");
  }

  @Test
  @DisplayName("같은 마이크로초 안의 그룹 상충은 정밀도와 무관하게 제한한다")
  void subMicrosecondGroupConflictRestricts() {
    scenario.stage(group("C1", T1.plusNanos(100), "U1", "U2"));
    scenario.stage(group("C1", T1.plusNanos(900), "U2", "U3"));
    // then
    assertThat(scenario.staged().snapshot().groups().get("C1"))
        .isEqualTo(new GroupSnapshot("C1", Set.of("U2"), T1));
  }

  @Test
  @DisplayName("같은 마이크로초 안의 신원 삭제 관측은 복원하지 않는다")
  void subMicrosecondIdentityConflictRestricts() {
    scenario.stage(
        new SourceIdentityRecord("U2", Optional.empty(), false, false, true, T1.plusNanos(100)));
    scenario.stage(identity("U2", T1.plusNanos(900)));
    // then
    assertThat(scenario.staged().snapshot().identities().get("U2"))
        .isEqualTo(new SourceIdentityRecord("U2", Optional.empty(), false, false, true, T1));
  }

  @ParameterizedTest
  @CsvSource({"true,false", "false,true"})
  @DisplayName("미완료 신원의 guest 또는 deleted 회수는 기존 읽기 키도 제거한다")
  void incompleteIdentityRestrictionRevokesReadKeys(boolean guest, boolean deleted) {
    scenario.existing();
    scenario.stage(new SourceIdentityRecord("U2", Optional.empty(), false, guest, deleted, T1));
    scenario.merge(AccessMerge.INCOMPLETE);
    // then
    assertThat(scenario.keys()).isEmpty();
  }

  static GroupSnapshot group(String id, Instant at, String... members) {
    return id.startsWith("~")
        ? GroupSnapshot.synthetic(id, Set.of(members), at)
        : new GroupSnapshot(id, Set.of(members), at);
  }

  static SourceIdentityRecord identity(String id, Instant at) {
    return new SourceIdentityRecord(id, Optional.empty(), false, false, false, at);
  }
}
