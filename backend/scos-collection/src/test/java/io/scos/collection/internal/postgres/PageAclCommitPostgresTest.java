package io.scos.collection.internal.postgres;

import static io.scos.collection.internal.AclCommitScenario.GRANTED;
import static io.scos.collection.internal.AclCommitScenario.ID;
import static io.scos.collection.internal.AclCommitScenario.SOURCE;
import static io.scos.collection.internal.AclCommitScenario.T1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.AclCommitScenario;
import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.PageAclCommitContract;
import io.scos.collection.internal.RawRecord;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL PageAclCommitPostgresTest 공통 계약과 롤백")
class PageAclCommitPostgresTest extends PageAclCommitContract {
  @Override
  protected AclCommitScenario newScenario() {
    PostgresSupport.truncateAll();
    var ds = PostgresSupport.dataSource();
    return new AclCommitScenario(
        CommitContext.Ports.postgres(ds), new PostgresBodyCompletionStore(JdbcClient.create(ds)));
  }

  @Test
  @DisplayName("outbox 실패는 본문 경로의 ACL과 본문 변경도 되돌린다")
  void pageAclAndBodyRollbackTogether() {
    scenario.failOutbox();
    // then
    assertThatThrownBy(
            () ->
                scenario.collect(
                    scenario.item(RawAcl.observed(Status.FAILED, Set.of(), T1)).version("v2"),
                    true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected ACL outbox failure");
    // then
    assertThat(
            List.of(
                scenario.acl(),
                ((RawRecord.Active) scenario.ports.rawItems().get(SOURCE, ID).orElseThrow())
                    .item()
                    .sourceVersion(),
                scenario.requestVersion()))
        .containsExactly(GRANTED, "v1", 1L);
  }

  @Test
  @DisplayName("본문 삭제 뒤 outbox 실패는 원본과 ACL 삭제도 되돌린다")
  void deleteAndAclRollbackTogether() {
    scenario.failOutbox();
    // when·then
    assertThatThrownBy(() -> scenario.explicitDelete(Optional.empty()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected ACL outbox failure");
    assertThat(
            List.of(
                scenario.acl(),
                scenario.ports.rawItems().get(SOURCE, ID).orElseThrow()
                    instanceof RawRecord.Active))
        .containsExactly(GRANTED, true);
  }
}
