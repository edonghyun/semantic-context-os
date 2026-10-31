package io.scos.store.memory;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.store.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 메모리 어댑터에 위임하며 성공한 사용 기록 배치를 관찰한다. */
public final class ObservedUserTokens implements UserTokens {
  private final MemoryIdentityStore identities = new MemoryIdentityStore();
  private final MemoryUserTokens tokens = new MemoryUserTokens(identities);
  private final List<Map<TokenHash, Instant>> writes = new ArrayList<>();
  private final Runnable beforeUsageWrite;
  private Optional<RuntimeException> authenticationFailure = Optional.empty();
  private Optional<Error> authenticationError = Optional.empty();

  public ObservedUserTokens() {
    this(() -> {});
  }

  /** 저장 직전의 지연·장애만 주입하며 실제 저장 의미는 메모리 어댑터에 위임한다. */
  public ObservedUserTokens(Runnable beforeUsageWrite) {
    this.beforeUsageWrite = beforeUsageWrite;
  }

  public void seed(AccessDirectory directory) {
    new MemoryIdentitySeed(identities).seed(directory);
  }

  public void failAuthentication(RuntimeException failure) {
    authenticationFailure = Optional.of(failure);
  }

  public void failAuthentication(Error failure) {
    authenticationError = Optional.of(failure);
  }

  @Override
  public Optional<Principal> authenticate(TokenHash hash) {
    authenticationError.ifPresent(
        failure -> {
          throw failure;
        });
    authenticationFailure.ifPresent(
        failure -> {
          throw failure;
        });
    return tokens.authenticate(hash);
  }

  @Override
  public void store(TokenHash hash, String userId, String label, Instant at) {
    tokens.store(hash, userId, label, at);
  }

  @Override
  public void revoke(TokenHash hash, Instant at) {
    tokens.revoke(hash, at);
  }

  @Override
  public void revokeAllOf(String userId, Instant at) {
    tokens.revokeAllOf(userId, at);
  }

  @Override
  public void markUsed(Map<TokenHash, Instant> lastUsed) {
    beforeUsageWrite.run();
    tokens.markUsed(lastUsed);
    writes.add(Map.copyOf(lastUsed));
  }

  public List<Map<TokenHash, Instant>> writes() {
    return List.copyOf(writes);
  }

  public Optional<Instant> lastUsedAt(TokenHash hash) {
    return tokens.lastUsedAt(hash);
  }

  public Optional<Instant> revokedAt(TokenHash hash) {
    return tokens.revokedAt(hash);
  }
}
