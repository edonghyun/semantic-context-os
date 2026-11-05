package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("유닛 배치는 문서 소유를 한 문장으로 확인한다")
class UnitOwnershipBatchTest {
  @Test
  @DisplayName("같은 문서의 여러 유닛을 포함해도 배치의 소유 확인은 한 문장이다")
  void batchClaimsOwnershipInOneStatement() {
    try (var scenario = new UnitOwnershipBatchScenario()) {
      // when
      scenario.upsertRepeatedDocument();
      // then
      assertThat(scenario.ownershipStatements()).isEqualTo(1);
    }
  }
}
