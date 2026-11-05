package io.scos.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AclStore;
import io.scos.spi.access.AclStoreContract;
import io.scos.spi.access.DocumentAcl;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("ACL 저장소 계약 — SQLite 포트")
class SqliteAclStoreTest extends AclStoreContract {
  @TempDir Path dir;

  @Test
  @DisplayName("새 바이너리의 모르는 실패 원인은 unknown으로 읽는다")
  void unknownStoredFailureReasonIsReadable() {
    store.put(A, List.of(new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(), OBSERVED)));
    JdbcClient.create(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("index.sqlite")))
        .sql("UPDATE document_acl SET reason = 'FUTURE_POLICY'")
        .update();
    // then
    assertThat(store.get(A, List.of("doc")).get("doc").reason().wire()).isEqualTo("unknown");
  }

  @Override
  protected AclStore newStore() {
    return SqliteIndex.open(dir.resolve("index.sqlite")).acl();
  }
}
