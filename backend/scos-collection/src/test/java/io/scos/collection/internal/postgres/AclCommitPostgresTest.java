package io.scos.collection.internal.postgres;

import static io.scos.collection.internal.AclCommitScenario.GRANTED;
import static io.scos.collection.internal.AclCommitScenario.T1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.AclCommitContract;
import io.scos.collection.internal.AclCommitScenario;
import io.scos.collection.internal.CommitContext;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL AclCommitPostgresTest 공통 계약과 롤백")
class AclCommitPostgresTest extends AclCommitContract {
  @Override
  protected AclCommitScenario newScenario() {
    PostgresSupport.truncateAll();
    var ds = PostgresSupport.dataSource();
    return new AclCommitScenario(
        CommitContext.Ports.postgres(ds), new PostgresBodyCompletionStore(JdbcClient.create(ds)));
  }

  @Test
  @DisplayName("AclCommit의 동시각 신원 상충은 제한된 payload와 상충 표식으로 보관된다")
  void conflictingIdentityIsActuallyStaged() {
    scenario.domainSharedFileAndVerifiedIdentity();
    scenario.conflictingHiddenIdentityRestrictsFile();
    // then
    assertThat(
            JdbcClient.create(PostgresSupport.dataSource())
                .sql(
                    """
            SELECT count(*) FROM access_observations
            WHERE run_id = 'acl-run' AND kind = 'identity' AND key = 'U7' AND conflicted
              AND payload @> '{"sourceUserId":"U7","email":null,"emailVerified":false,"guest":false,"deleted":false}'::jsonb
            """)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("outbox 실패는 ACL 갱신도 같은 트랜잭션에서 되돌린다")
  void changedAclAndOutboxRollbackTogether() {
    scenario.failOutbox();
    // then
    assertThatThrownBy(() -> scenario.access(RawAcl.observed(Status.FAILED, Set.of(), T1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected ACL outbox failure");
    // then
    assertThat(List.of(scenario.acl(), scenario.requestVersion())).containsExactly(GRANTED, 1L);
  }
}
