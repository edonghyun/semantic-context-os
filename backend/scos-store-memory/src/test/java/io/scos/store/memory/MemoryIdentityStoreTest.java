package io.scos.store.memory;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.IdentityStoreContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 신원 저장소는 공통 접근 키 계약을 지킨다")
class MemoryIdentityStoreTest extends IdentityStoreContract {
  private MemoryIdentityStore memory;

  @Override
  protected IdentityStore newStore() {
    memory = new MemoryIdentityStore();
    return memory;
  }

  @Override
  protected void seed(AccessDirectory directory) {
    new MemoryIdentitySeed(memory).seed(directory);
  }
}
