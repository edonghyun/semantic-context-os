package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.store.DocumentOwnership.Claim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("첫 소유 경합은 출처를 직렬화하고 단일 소유자를 정한다")
class PostgresDocumentOwnershipRaceTest {
  @Test
  @DisplayName("미커밋 첫 소유에 다른 출처는 마감까지 기다린다")
  void contenderWaitsForOwnerCommit() throws Exception {
    try (var scenario = new DocumentOwnershipRaceScenario()) {
      var result = scenario.contend();

      // then
      assertThat(result.loserFinishedBeforeCommit()).isFalse();
    }
  }

  @Test
  @DisplayName("경합의 승자는 하나이고 다른 출처는 OTHER_SOURCE를 받는다")
  void concurrentFirstClaimHasOneOwner() throws Exception {
    try (var scenario = new DocumentOwnershipRaceScenario()) {
      var result = scenario.contend();

      // then
      assertThat(result.winner()).isEqualTo(Claim.OWNED);
      assertThat(result.loser()).isEqualTo(Claim.OTHER_SOURCE);
    }
  }
}
