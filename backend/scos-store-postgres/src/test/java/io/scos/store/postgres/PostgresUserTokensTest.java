package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.access.UserTokensContract;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 사용자 토큰은 공통 계약을 지킨다")
class PostgresUserTokensTest extends UserTokensContract {
  private UserTokenReadSource read;

  @Override
  protected UserTokens newTokens() {
    PostgresSupport.truncateAll();
    read = new UserTokenReadSource();
    return new PostgresIndexConfiguration().newUserTokens(read, PostgresSupport.dataSource());
  }

  @Override
  protected void seed(AccessDirectory directory) {
    new PostgresAccessSeed(PostgresSupport.dataSource()).seed(directory);
  }

  @Override
  protected void setActive(String userId, boolean active) {
    JdbcClient.create(PostgresSupport.dataSource())
        .sql("UPDATE users SET active = :active WHERE id = :user")
        .param("active", active)
        .param("user", userId)
        .update();
  }

  @Override
  protected Optional<Instant> lastUsedAt(TokenHash hash) {
    return JdbcClient.create(PostgresSupport.dataSource())
        .sql("SELECT last_used_at FROM user_tokens WHERE token_hash = :hash")
        .param("hash", hash.value())
        .query(
            (row, index) ->
                Optional.ofNullable(row.getTimestamp("last_used_at")).map(t -> t.toInstant()))
        .single();
  }

  @Override
  protected Optional<Instant> revokedAt(TokenHash hash) {
    return JdbcClient.create(PostgresSupport.dataSource())
        .sql("SELECT revoked_at FROM user_tokens WHERE token_hash = :hash")
        .param("hash", hash.value())
        .query(
            (row, index) ->
                Optional.ofNullable(row.getTimestamp("revoked_at")).map(t -> t.toInstant()))
        .single();
  }

  @Test
  @DisplayName("인증은 읽기 연결을 사용한다")
  void authenticationUsesReadConnection() {
    tokens.store(TOKEN, ALICE.id(), "work", CREATED);
    int before = read.openedConnections();

    tokens.authenticate(TOKEN);

    // then
    assertThat(read.openedConnections()).isGreaterThan(before);
  }

  @Test
  @DisplayName("계약의 읽기 연결은 실제로 쓰기를 거부한다")
  void readConnectionRejectsWrites() {
    var jdbc = JdbcClient.create(read);

    // when·then
    assertThatThrownBy(() -> jdbc.sql("UPDATE users SET active = false").update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("read-only");
  }
}
