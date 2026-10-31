package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.store.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사용자 토큰 어댑터가 함께 지키는 인증·폐기·사용 기록 계약. */
@DisplayName("사용자 토큰 저장소는 활성 사용자의 미폐기 해시만 인증한다")
public abstract class UserTokensContract {
  protected static final User ALICE = new User("alice", "alice@example.com", true);
  protected static final User BOB = new User("bob", "bob@example.com", true);
  protected static final TokenHash TOKEN = TokenHash.of("contract-token");
  protected static final TokenHash SECOND = TokenHash.of("second-token");
  protected static final TokenHash BOBS_TOKEN = TokenHash.of("bob-token");
  protected static final Instant CREATED = Instant.parse("2026-10-27T00:00:00Z");
  protected UserTokens tokens;

  protected abstract UserTokens newTokens();

  protected abstract void seed(AccessDirectory directory);

  protected abstract void setActive(String userId, boolean active);

  protected abstract Optional<Instant> lastUsedAt(TokenHash hash);

  protected abstract Optional<Instant> revokedAt(TokenHash hash);

  @BeforeEach
  void createStore() {
    tokens = newTokens();
    seed(AccessDirectory.empty().withUser(ALICE).withUser(BOB));
  }

  @Test
  @DisplayName("미등록 사용자의 토큰 저장은 거부한다")
  void rejectsUnknownUser() {
    // when·then
    assertThatThrownBy(() -> tokens.store(TOKEN, "unknown", "work", CREATED))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("비활성 등록 사용자에게 저장한 토큰은 재활성화 후 인증된다")
  void storesForInactiveRegisteredUser() {
    // given
    setActive(ALICE.id(), false);
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    setActive(ALICE.id(), true);

    // when
    var principal = tokens.authenticate(TOKEN);

    // then
    assertThat(principal).contains(new Principal("alice"));
  }

  @Test
  @DisplayName("저장한 토큰은 그 사용자의 principal로 인증된다")
  void authenticatesStoredToken() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);

    var principal = tokens.authenticate(TOKEN);

    // then
    assertThat(principal).contains(new Principal(ALICE.id()));
  }

  @Test
  @DisplayName("모르는 해시는 인증되지 않는다")
  void unknownHashIsAbsent() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);

    var principal = tokens.authenticate(TokenHash.of("unknown-token"));

    // then
    assertThat(principal).isEmpty();
  }

  @Test
  @DisplayName("폐기한 토큰은 인증되지 않는다")
  void revokedTokenIsAbsent() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);

    tokens.revoke(TOKEN, CREATED.plusSeconds(1));

    // then
    assertThat(tokens.authenticate(TOKEN)).isEmpty();
  }

  @Test
  @DisplayName("이미 폐기한 토큰을 다시 폐기해도 최초 시각을 보존한다")
  void repeatedRevokeKeepsFirstTime() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    tokens.revoke(TOKEN, CREATED);

    tokens.revoke(TOKEN, CREATED.plusSeconds(1));

    // then
    assertThat(revokedAt(TOKEN)).contains(CREATED);
  }

  @Test
  @DisplayName("일괄 폐기는 이미 폐기된 토큰의 최초 시각을 보존한다")
  void revokeAllKeepsFirstTime() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    tokens.revoke(TOKEN, CREATED);

    tokens.revokeAllOf(ALICE.id(), CREATED.plusSeconds(1));

    // then
    assertThat(revokedAt(TOKEN)).contains(CREATED);
  }

  @Test
  @DisplayName("비활성 사용자의 토큰은 인증되지 않는다")
  void inactiveUserIsAbsent() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    setActive(ALICE.id(), false);

    var principal = tokens.authenticate(TOKEN);

    // then
    assertThat(principal).isEmpty();
  }

  @Test
  @DisplayName("사용자의 토큰을 모두 폐기하면 어느 토큰도 인증되지 않는다")
  void revokeAllRemovesEveryTokenOfUser() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    tokens.store(SECOND, ALICE.id(), "home", CREATED);

    tokens.revokeAllOf(ALICE.id(), CREATED.plusSeconds(1));

    // then
    assertThat(List.of(tokens.authenticate(TOKEN), tokens.authenticate(SECOND)))
        .allSatisfy(principal -> assertThat(principal).isEmpty());
  }

  @Test
  @DisplayName("일괄 폐기는 다른 사용자의 토큰을 보존한다")
  void revokeAllKeepsOtherUser() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    tokens.store(BOBS_TOKEN, BOB.id(), "work", CREATED);

    tokens.revokeAllOf(ALICE.id(), CREATED.plusSeconds(1));

    // then
    assertThat(tokens.authenticate(BOBS_TOKEN)).contains(new Principal(BOB.id()));
  }

  @Test
  @DisplayName("사용자를 다시 활성화해도 일괄 폐기한 토큰은 살아나지 않는다")
  void reactivationDoesNotRestoreRevokedTokens() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    setActive(ALICE.id(), false);
    tokens.revokeAllOf(ALICE.id(), CREATED.plusSeconds(1));
    setActive(ALICE.id(), true);

    var principal = tokens.authenticate(TOKEN);

    // then
    assertThat(principal).isEmpty();
  }

  @Test
  @DisplayName("첫 사용 시각을 기록한다")
  void recordsFirstUse() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    var used = CREATED.plusSeconds(1);

    tokens.markUsed(Map.of(TOKEN, used));

    // then
    assertThat(lastUsedAt(TOKEN)).contains(used);
  }

  @Test
  @DisplayName("뒤늦게 도착한 오래된 사용 기록은 시각을 되돌리지 않는다")
  void olderUseDoesNotMoveTimeBackwards() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    var latest = CREATED.plusSeconds(2);
    tokens.markUsed(Map.of(TOKEN, latest));

    tokens.markUsed(Map.of(TOKEN, CREATED.plusSeconds(1)));

    // then
    assertThat(lastUsedAt(TOKEN)).contains(latest);
  }

  @Test
  @DisplayName("더 늦은 사용 시각으로 갱신한다")
  void laterUseAdvancesTime() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    tokens.markUsed(Map.of(TOKEN, CREATED.plusSeconds(1)));
    var latest = CREATED.plusSeconds(2);

    tokens.markUsed(Map.of(TOKEN, latest));

    // then
    assertThat(lastUsedAt(TOKEN)).contains(latest);
  }

  @Test
  @DisplayName("같은 사용 시각을 다시 반영해도 값은 같다")
  void equalUseKeepsTime() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    tokens.markUsed(Map.of(TOKEN, CREATED));

    tokens.markUsed(Map.of(TOKEN, CREATED));

    // then
    assertThat(lastUsedAt(TOKEN)).contains(CREATED);
  }

  @Test
  @DisplayName("사용 기록 묶음은 각 토큰의 시각을 따로 반영한다")
  void recordsEveryTokenInBatch() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    tokens.store(SECOND, ALICE.id(), "home", CREATED);
    var later = CREATED.plusSeconds(1);

    tokens.markUsed(Map.of(TOKEN, CREATED, SECOND, later));

    // then
    assertThat(Map.of(TOKEN, lastUsedAt(TOKEN), SECOND, lastUsedAt(SECOND)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(TOKEN, Optional.of(CREATED), SECOND, Optional.of(later)));
  }

  @Test
  @DisplayName("빈 사용 기록 묶음은 기존 시각을 보존한다")
  void emptyBatchKeepsTime() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    tokens.markUsed(Map.of(TOKEN, CREATED));

    tokens.markUsed(Map.of());

    // then
    assertThat(lastUsedAt(TOKEN)).contains(CREATED);
  }

  @Test
  @DisplayName("사용 기록은 폐기한 토큰을 다시 인증하지 않는다")
  void usageDoesNotRestoreRevokedToken() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    tokens.revoke(TOKEN, CREATED);

    tokens.markUsed(Map.of(TOKEN, CREATED.plusSeconds(1)));

    // then
    assertThat(tokens.authenticate(TOKEN)).isEmpty();
  }

  @Test
  @DisplayName("같은 해시를 다시 저장하면 거부하고 기존 사용자를 보존한다")
  void duplicateHashDoesNotReplaceOwner() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);

    // when·then
    assertThatThrownBy(() -> tokens.store(TOKEN, BOB.id(), "duplicate", CREATED))
        .isInstanceOf(RuntimeException.class);
    assertThat(tokens.authenticate(TOKEN)).contains(new Principal(ALICE.id()));
  }
}
