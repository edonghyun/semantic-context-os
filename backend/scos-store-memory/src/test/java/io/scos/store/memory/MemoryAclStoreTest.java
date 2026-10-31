package io.scos.store.memory;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.scos.spi.access.AclStore;
import io.scos.spi.access.AclStoreContract;
import io.scos.spi.access.DocumentAcl;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("메모리 ACL 저장소는 공통 계약을 지킨다")
class MemoryAclStoreTest extends AclStoreContract {
  @Override
  protected AclStore newStore() {
    return new MemoryAclStore();
  }

  @Test
  @DisplayName("삭제와 겹친 ACL 조회도 예외 없이 완료된다")
  void readsDuringDeletion() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    var scenario = new AclReadDeleteScenario(store, A, acl);

    // when·then
    assertThatCode(scenario::readDuringDeletes).doesNotThrowAnyException();
  }
}
