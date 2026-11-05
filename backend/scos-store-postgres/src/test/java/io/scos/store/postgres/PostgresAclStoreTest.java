package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.AclStoreContract;
import io.scos.spi.access.DocumentAcl;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("db")
@DisplayName("PostgreSQL ACL 저장소는 공통 계약을 지킨다")
class PostgresAclStoreTest extends AclStoreContract {
  @Override
  protected AclStore newStore() {
    PostgresSupport.truncateAll();
    return new PostgresAclStore(JdbcClient.create(PostgresSupport.dataSource()));
  }

  @Test
  @DisplayName("새 바이너리의 모르는 실패 원인은 unknown으로 읽는다")
  void unknownStoredFailureReasonIsReadable() {
    store.put(A, List.of(new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(), OBSERVED)));
    JdbcClient.create(PostgresSupport.dataSource())
        .sql("UPDATE document_acl SET reason = 'FUTURE_POLICY'")
        .update();
    // then
    assertThat(store.get(A, List.of("doc")).get("doc").reason().wire()).isEqualTo("unknown");
  }

  @Test
  @DisplayName("호출자 트랜잭션이 롤백되면 ACL 쓰기도 사라진다")
  void rollsBackWithCaller() {
    var tx =
        new TransactionTemplate(new DataSourceTransactionManager(PostgresSupport.dataSource()));

    tx.executeWithoutResult(
        status -> {
          store.put(
              A, List.of(new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED)));
          status.setRollbackOnly();
        });

    // then
    assertThat(store.get(A, List.of("doc"))).isEmpty();
  }
}
