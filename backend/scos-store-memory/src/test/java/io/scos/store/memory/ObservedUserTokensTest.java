package io.scos.store.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.access.UserTokensContract;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("사용 기록 관찰 어댑터도 사용자 토큰 공통 계약을 지킨다")
class ObservedUserTokensTest extends UserTokensContract {
  private ObservedUserTokens observed;
  private AccessDirectory directory;
  private final AtomicBoolean failUsage = new AtomicBoolean();

  @Override
  protected UserTokens newTokens() {
    observed = new ObservedUserTokens(this::beforeUsageWrite);
    return observed;
  }

  @Override
  protected void seed(AccessDirectory directory) {
    this.directory = directory;
    observed.seed(directory);
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
  protected Optional<Instant> lastUsedAt(TokenHash hash) {
    return observed.lastUsedAt(hash);
  }

  @Override
  protected Optional<Instant> revokedAt(TokenHash hash) {
    return observed.revokedAt(hash);
  }

  @Test
  @DisplayName("사용 기록 장애는 저장 전에 호출자에게 전달된다")
  void usageFailureIsPropagated() {
    failUsage.set(true);

    // when·then
    assertThatThrownBy(() -> tokens.markUsed(Map.of(TOKEN, CREATED)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("usage-write-fixture");
  }

  @Test
  @DisplayName("저장 전 장애가 난 사용 시각은 저장되지 않는다")
  void failedUsageIsNotPersisted() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    failUsage.set(true);

    try {
      tokens.markUsed(Map.of(TOKEN, CREATED));
    } catch (IllegalStateException expected) {
      // 장애 이후의 저장 상태를 확인한다.
    }

    // then
    assertThat(lastUsedAt(TOKEN)).isEmpty();
  }

  private void beforeUsageWrite() {
    if (failUsage.getAndSet(false)) throw new IllegalStateException("usage-write-fixture");
  }
}
