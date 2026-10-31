package io.scos.store.memory;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.access.UserTokensContract;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 사용자 토큰은 공통 계약을 지킨다")
class MemoryUserTokensTest extends UserTokensContract {
  private MemoryIdentityStore identities;
  private MemoryUserTokens memory;
  private AccessDirectory directory;

  @Override
  protected UserTokens newTokens() {
    identities = new MemoryIdentityStore();
    memory = new MemoryUserTokens(identities);
    return memory;
  }

  @Override
  protected void seed(AccessDirectory directory) {
    this.directory = directory;
    new MemoryIdentitySeed(identities).seed(directory);
  }

  @Override
  protected void setActive(String userId, boolean active) {
    seed(
        new AccessDirectory(
            directory.users().stream()
                .map(u -> u.id().equals(userId) ? new User(u.id(), u.email(), active) : u)
                .toList(),
            directory.identities(),
            directory.links(),
            directory.groups(),
            directory.scopes()));
  }

  @Override
  protected Optional<Instant> revokedAt(TokenHash hash) {
    return memory.revokedAt(hash);
  }

  @Override
  protected Optional<Instant> lastUsedAt(TokenHash hash) {
    return memory.lastUsedAt(hash);
  }
}
