package io.scos.store.postgres;

import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.store.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 인증 조회는 읽기 연결, 등록·폐기·사용 기록은 쓰기 연결로 수행한다. */
public final class PostgresUserTokens implements UserTokens {
  private final JdbcClient read;
  private final JdbcClient write;

  public PostgresUserTokens(DataSource read, DataSource write) {
    this.read = JdbcClient.create(read);
    this.write = JdbcClient.create(write);
  }

  @Override
  public Optional<Principal> authenticate(TokenHash hash) {
    return read.sql(
            "SELECT u.id FROM user_tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = :hash AND t.revoked_at IS NULL AND u.active")
        .param("hash", hash.value())
        .query(String.class)
        .optional()
        .map(Principal::new);
  }

  @Override
  public void store(TokenHash hash, String userId, String label, Instant at) {
    write
        .sql(
            "INSERT INTO user_tokens (token_hash, user_id, label, created_at) VALUES (:hash, :user, :label, :at)")
        .param("hash", hash.value())
        .param("user", userId)
        .param("label", label)
        .param("at", Timestamp.from(at))
        .update();
  }

  @Override
  public void revoke(TokenHash hash, Instant at) {
    write
        .sql(
            "UPDATE user_tokens SET revoked_at = :at WHERE token_hash = :hash AND revoked_at IS NULL")
        .param("at", Timestamp.from(at))
        .param("hash", hash.value())
        .update();
  }

  @Override
  public void revokeAllOf(String userId, Instant at) {
    write
        .sql("UPDATE user_tokens SET revoked_at = :at WHERE user_id = :user AND revoked_at IS NULL")
        .param("at", Timestamp.from(at))
        .param("user", userId)
        .update();
  }

  @Override
  public void markUsed(Map<TokenHash, Instant> lastUsed) {
    lastUsed.forEach(
        (hash, at) ->
            write
                .sql(
                    "UPDATE user_tokens SET last_used_at = :at WHERE token_hash = :hash AND (last_used_at IS NULL OR last_used_at < :at)")
                .param("at", Timestamp.from(at))
                .param("hash", hash.value())
                .update());
  }
}
