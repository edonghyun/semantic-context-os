package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.store.SpaceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("문서 소유는 호출자 트랜잭션과 함께 롤백된다")
class PostgresDocumentOwnershipTransactionTest {
  private DocumentOwnershipScenario scenario;

  @BeforeEach
  void givenOwnership() {
    scenario = new DocumentOwnershipScenario();
  }

  @Test
  @DisplayName("기존 소유 재확인도 다른 ACL 결정의 소유 잠금을 기다린다")
  void existingClaimSerializesAclDecisions() {
    var blocked = scenario.competingClaim();
    // then
    assertThat(blocked).contains("55P03");
  }

  @Test
  @DisplayName("기존 문서의 배치 소유 확인도 다른 ACL 결정의 잠금을 기다린다")
  void existingBatchSerializesAclDecisions() {
    var blocked = scenario.competingUnitWrite();
    // then
    assertThat(blocked).contains("55P03");
  }

  @Test
  @DisplayName("요청한 문서의 잠금 행이 없으면 배치 소유를 거부한다")
  void missingBatchOwnerIsRejected() {
    // when·then
    assertThatThrownBy(scenario::claimBatchWithMissingOwner)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("문서 소유 잠금 누락: 요청 1, 잠금 0");
  }

  @Test
  @DisplayName("유닛 없는 소유 등록은 호출자가 롤백하면 남지 않는다")
  void claimRollsBackWithCaller() {
    scenario.rollBackClaim();

    // then
    assertThat(scenario.sourceOf("doc-1")).isEmpty();
  }

  @Test
  @DisplayName("호출자가 롤백하면 배치 유닛과 소유 등록이 모두 남지 않는다")
  void unitOwnershipRollsBackWithCaller() {
    scenario.rollBackUnits();

    // then
    assertThat(scenario.sourceOf("doc-1")).isEmpty();
    assertThat(scenario.store().listAll(new SpaceId("a"))).isEmpty();
  }
}
