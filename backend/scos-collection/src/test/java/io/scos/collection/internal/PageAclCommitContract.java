package io.scos.collection.internal;

import static io.scos.collection.internal.AclCommitScenario.GRANTED;
import static io.scos.collection.internal.AclCommitScenario.ID;
import static io.scos.collection.internal.AclCommitScenario.SOURCE;
import static io.scos.collection.internal.AclCommitScenario.T0;
import static io.scos.collection.internal.AclCommitScenario.T1;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.LogCapture;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("페이지 커밋은 본문 결정과 별도로 ACL을 적용한다")
@Isolated
public abstract class PageAclCommitContract {
  protected AclCommitScenario scenario;

  protected abstract AclCommitScenario newScenario();

  @BeforeEach
  void setUp() {
    scenario = newScenario();
  }

  @Test
  @DisplayName("본문 경로의 미래 관측도 로컬 수신 시각으로 자른다")
  void bodyFutureObservationIsCapped() {
    scenario.collect(
        scenario.item(
            RawAcl.observed(Status.OK, GRANTED.keys(), scenario.clock.instant().plusSeconds(3600))),
        true);
    // then
    assertThat(scenario.acl().observedAt()).isEqualTo(scenario.clock.instant());
  }

  @Test
  @DisplayName("본문이 unchanged여도 ACL 변경은 투영을 요청한다")
  void unchangedBodyStillRequestsAclProjection() {
    var result = scenario.collect(scenario.item(RawAcl.observed(Status.OK, Set.of(), T1)), true);
    // then
    assertThat(List.of(result.unchanged(), scenario.acl(), scenario.requestVersion()))
        .containsExactly(1, new RawAcl.Observed(Status.OK, Set.of(), T1), 2L);
  }

  @Test
  @DisplayName("과거 순서로 거부된 본문도 새 ACL은 저장한다")
  void staleBodyStillStoresNewAcl() {
    var order = new SourceOrder("message.v1", "0002");
    scenario
        .ports
        .rawItems()
        .apply(SOURCE, scenario.item(GRANTED).version("v2").order(order).upsert());
    scenario.collect(
        scenario
            .item(RawAcl.observed(Status.FAILED, Set.of(), T1))
            .order(new SourceOrder("message.v1", "0001")),
        true);
    // then
    assertThat(scenario.acl()).isEqualTo(new RawAcl.Observed(Status.FAILED, Set.of(), T1));
  }

  @Test
  @DisplayName("collect의 다른 instance 키는 FAILED가 된다")
  void wrongInstanceFromCollectIsFailed() {
    scenario.collect(
        scenario.item(RawAcl.observed(Status.OK, Set.of(AccessKey.parse("user:other:U1")), T1)),
        true);
    // then
    assertThat(scenario.acl())
        .isEqualTo(
            new RawAcl.Observed(Status.FAILED, Set.of(), T1, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("collect의 지원 원천 scope 키는 FAILED가 된다")
  void scopeFromCollectIsFailed() {
    scenario.collect(
        scenario.item(
            RawAcl.observed(Status.OK, Set.of(AccessKey.parse("scope:acl-source:*")), T1)),
        true);
    // then
    assertThat(scenario.acl())
        .isEqualTo(
            new RawAcl.Observed(Status.FAILED, Set.of(), T1, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("collect의 지원 원천 UNSUPPORTED 상태는 FAILED가 된다")
  void unsupportedStatusOnSupportedSourceIsFailed() {
    scenario.collect(
        scenario.item(
            RawAcl.observed(Status.UNSUPPORTED, Set.of(AccessKey.parse("scope:acl-source:*")), T1)),
        true);
    // then
    assertThat(scenario.acl())
        .isEqualTo(
            new RawAcl.Observed(Status.FAILED, Set.of(), T1, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("본문과 ACL이 모두 바뀌어도 투영 요청은 한 번이다")
  void bodyAndAclChangesRequestProjectionOnce() {
    scenario.collect(
        scenario.item(RawAcl.observed(Status.FAILED, Set.of(), T1)).version("v2"), true);
    // then
    assertThat(scenario.requestVersion()).isEqualTo(2);
  }

  @Test
  @DisplayName("수집이 꺼져 있으면 새 본문도 ACL 행을 만들지 않는다")
  void disabledCollectDoesNotCreateAcl() {
    scenario.collect(RawItems.item("new").acl(GRANTED), false);
    // then
    assertThat(scenario.ports.rawAcls().get(SOURCE, List.of("new"))).isEmpty();
  }

  @Test
  @DisplayName("collect의 비운반 종류는 ACL 행을 만들지 않는다")
  void nonCarrierFromCollectDoesNotCreateAcl() {
    scenario.collect(RawItems.item("fragment").resourceType("slack.fragment").acl(GRANTED), true);
    // then
    assertThat(scenario.ports.rawAcls().get(SOURCE, List.of("fragment"))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"source:colon", "source%percent"})
  @DisplayName("비정규 instance도 본문은 커밋하고 ACL은 모르는 상태로 둔다")
  void nonCanonicalInstanceStillCommitsBody(String value) {
    // given
    var instance = new SourceInstanceId(value);
    // when
    scenario.collectForInstance(instance);
    // then
    assertThat(scenario.ports.rawItems().get(instance, ID).orElseThrow())
        .isInstanceOf(RawRecord.Active.class);
    assertThat(scenario.ports.rawAcls().get(instance, List.of(ID))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"source:colon", "source%percent"})
  @DisplayName("비정규 instance의 변경 없는 본문은 추가 투영을 요청하지 않는다")
  void nonCanonicalUnchangedBodyDoesNotRequestProjection(String value) {
    // given
    var instance = new SourceInstanceId(value);
    // when
    scenario.unchangedBodyForInstance(instance);
    // then
    assertThat(scenario.ports.outbox().request(instance).orElseThrow().version()).isEqualTo(1L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"source:colon", "source%percent"})
  @DisplayName("비정규 instance의 ACL 생략은 원천과 이유를 경고로 남긴다")
  void nonCanonicalInstanceLogsSkippedAcl(String value) {
    try (var log = LogCapture.of(AclCommit.class)) {
      // when
      scenario.collectForInstance(new SourceInstanceId(value));
      // then
      assertThat(log.events("collection.acl.skipped"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(
                          List.of(
                              event.path("level").asString(),
                              event.path("sourceInstanceId").asString(),
                              event.path("detail").path("reason").asString()))
                      .containsExactly("warn", value, "non_canonical_instance"));
    }
  }

  @Test
  @DisplayName("명시적 삭제는 실제 raw tombstone과 함께 ACL 행을 지운다")
  void explicitDeleteRemovesRawAcl() {
    // given: ACL 존재를 먼저 확인한다.
    assertThat(scenario.acl()).as("given ACL 존재").isEqualTo(GRANTED);
    scenario.explicitDelete(Optional.empty());
    // then
    assertThat(scenario.acl()).isNull();
  }

  @Test
  @DisplayName("전체 스냅샷 부재는 실제 raw tombstone과 함께 ACL 행을 지운다")
  void snapshotAbsenceRemovesRawAcl() {
    // given: ACL 존재를 먼저 확인한다.
    assertThat(scenario.acl()).as("given ACL 존재").isEqualTo(GRANTED);
    scenario.snapshotAbsence();
    // then
    assertThat(scenario.acl()).isNull();
  }

  @Test
  @DisplayName("본문 부분 부재는 실제 raw tombstone과 함께 ACL 행을 지운다")
  void bodyAbsenceRemovesRawAcl() {
    // given: ACL 존재를 먼저 확인한다.
    assertThat(scenario.acl()).as("given ACL 존재").isEqualTo(GRANTED);
    scenario.bodyAbsence();
    // then
    assertThat(scenario.acl()).isNull();
  }

  @Test
  @DisplayName("과거 명시적 삭제가 unchanged면 active 원본의 ACL을 유지한다")
  void staleDeletePreservesRawAcl() {
    scenario
        .ports
        .rawItems()
        .apply(
            SOURCE,
            scenario
                .item(GRANTED)
                .version("v2")
                .order(new SourceOrder("message.v1", "0002"))
                .upsert());
    scenario.explicitDelete(Optional.of(new SourceOrder("message.v1", "0001")));
    // then
    assertThat(scenario.acl()).isEqualTo(GRANTED);
  }

  @Test
  @DisplayName("본문 삭제는 같은 externalId를 쓰는 다른 소스의 ACL을 보존한다")
  void deletePreservesOtherSourceAcl() {
    var other = new SourceInstanceId("other");
    scenario.ports.rawItems().apply(other, RawItems.item(ID).upsert());
    scenario
        .ports
        .rawAcls()
        .observe(
            other,
            ID,
            RawAcl.observed(Status.OK, Set.of(AccessKey.parse("user:other:U2")), T0),
            T0);
    scenario.explicitDelete(Optional.empty());
    // then
    assertThat(scenario.ports.rawAcls().get(other, List.of(ID)))
        .containsEntry(
            ID, new RawAcl.Observed(Status.OK, Set.of(AccessKey.parse("user:other:U2")), T0));
  }

  @Test
  @DisplayName("비운반 종류는 같은 ID의 기존 운반 원본 ACL을 바꾸지 않는다")
  void nonCarrierCannotChangeExistingCarrierAcl() {
    scenario.collectNonCarrierCollision();
    // then
    assertThat(scenario.acl()).isEqualTo(GRANTED);
  }

  @Test
  @DisplayName("비운반 종류의 같은 ID 관측은 투영 요청을 다시 만들지 않는다")
  void nonCarrierCollisionDoesNotRequestProjection() {
    scenario.collectNonCarrierCollision();
    // then
    assertThat(scenario.requestVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("본문과 ACL 의미가 같으면 투영 요청 버전을 유지한다")
  void identicalAclFromCollectDoesNotRequestProjection() {
    scenario.collect(scenario.item(RawAcl.observed(Status.OK, GRANTED.keys(), T1)), true);
    // then
    assertThat(scenario.requestVersion()).isEqualTo(1);
  }
}
