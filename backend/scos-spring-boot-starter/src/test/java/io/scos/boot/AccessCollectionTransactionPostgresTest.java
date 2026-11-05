package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("접근 수집 쓰기는 같은 PostgreSQL 트랜잭션에 참여한다")
class AccessCollectionTransactionPostgresTest {
  @Test
  @DisplayName("ACL·접근 관측·outbox는 같은 쓰기 트랜잭션으로 롤백된다")
  void sharesWriteDataSource() {
    PostgresSupport.truncateAll();
    var scenario = new AccessWriteTransactionScenario();
    scenario.rollbackAccessWrites();
    // then
    assertThat(scenario.rawAcls()).isEmpty();
    assertThat(scenario.observations()).isZero();
    assertThat(scenario.projectionPending()).isFalse();
  }
}
